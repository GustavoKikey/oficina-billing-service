# =====================================================================
# Implanta o Billing Service no EKS
# ---------------------------------------------------------------------
# Chamado pelo cd.yml depois que o CI passou na main, e também rodável à
# mão (credenciais do Learner Lab no ~/.aws). Lê tudo do contrato no SSM:
#
#   plataforma (oficina-infra-k8s) -> eks/cluster-name, RabbitMQ
#   este repo (infra/)             -> billing/tabela (DynamoDB)
#   OS Service (infra/)            -> jwt/public-key (este serviço só valida)
#
# Opcional, por variável de ambiente:
#   MP_ACCESS_TOKEN  -> Mercado Pago de verdade (conta de teste); sem ele,
#                       checkout simulado no LB público (RFC 004 §5)
#
# Uso:  bash scripts/implantar.sh hom 123456789012.dkr.ecr.us-east-1.amazonaws.com/oficina-billing-service:abc1234
# =====================================================================
set -euo pipefail

AMBIENTE="${1:?informe o ambiente (hom|prod)}"
IMAGEM="${2:?informe a imagem (registry/repositorio:tag)}"
REGIAO="${AWS_REGION:-us-east-1}"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="$(command -v python3 || command -v python)"
RESUMO="${GITHUB_STEP_SUMMARY:-/dev/null}"
NS=oficina
APP=oficina-billing
CONFIGMAP=oficina-billing-config

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; [ -n "${PF:-}" ] && kill "$PF" 2>/dev/null || true' EXIT

ssm() { aws ssm get-parameter --name "/oficina/${AMBIENTE}/$1" --with-decryption --query Parameter.Value --output text; }
segredo() { aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text; }
campo() { "$PY" -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]], end="")' "$1"; }

# secret <nome> <chave>=<arquivo>...  — arquivos, para a senha não ir para a linha de comando.
secret() {
  local nome="$1"; shift
  local args=()
  for par in "$@"; do args+=("--from-file=$par"); done
  kubectl -n "$NS" create secret generic "$nome" "${args[@]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "   $nome"
}

# Endereço DNS de um Service LoadBalancer (a AWS leva de segundos a minutos para atribuir).
endereco_lb() {
  local h
  for _ in $(seq 1 60); do
    h=$(kubectl -n "$NS" get svc "$1" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
    if [ -n "$h" ]; then echo "$h"; return 0; fi
    sleep 5
  done
  return 1
}

echo "== Cluster"
aws eks update-kubeconfig --region "$REGIAO" --name "$(ssm eks/cluster-name)" >/dev/null
kubectl apply -f "$RAIZ/k8s/00-namespace.yaml" >/dev/null

echo "== Secrets"
ssm jwt/public-key >"$TMP/publicKey.pem"
secret oficina-jwt-publica publicKey.pem="$TMP/publicKey.pem"

# O RabbitMQ (oficina-billing-rabbitmq) é da plataforma: scripts/plataforma-k8s.sh.
if ! kubectl -n "$NS" get secret oficina-billing-rabbitmq >/dev/null 2>&1; then
  echo "Secret oficina-billing-rabbitmq ausente: aplique a plataforma antes (oficina-infra-k8s)." >&2
  exit 1
fi

otel_desligado=true
if [ -n "${NEW_RELIC_LICENSE_KEY:-}" ]; then
  printf '%s' "$NEW_RELIC_LICENSE_KEY" >"$TMP/nr"
  secret oficina-newrelic license-key="$TMP/nr"
  otel_desligado=false
fi

# DynamoDB: credencial temporária da sessão do Learner Lab (ADR 012) — no
# lab não há IRSA. Vence com a sessão; o próximo deploy a renova.
aws configure export-credentials --format process >"$TMP/aws.json"
campo AccessKeyId <"$TMP/aws.json" >"$TMP/AWS_ACCESS_KEY_ID"
campo SecretAccessKey <"$TMP/aws.json" >"$TMP/AWS_SECRET_ACCESS_KEY"
campo SessionToken <"$TMP/aws.json" >"$TMP/AWS_SESSION_TOKEN"
secret oficina-billing-aws AWS_ACCESS_KEY_ID="$TMP/AWS_ACCESS_KEY_ID" \
  AWS_SECRET_ACCESS_KEY="$TMP/AWS_SECRET_ACCESS_KEY" AWS_SESSION_TOKEN="$TMP/AWS_SESSION_TOKEN"

if [ -n "${MP_ACCESS_TOKEN:-}" ]; then
  printf '%s' "$MP_ACCESS_TOKEN" >"$TMP/mp"
  secret oficina-billing-mercadopago access-token="$TMP/mp"
  provedor=mercadopago
else
  kubectl -n "$NS" delete secret oficina-billing-mercadopago --ignore-not-found >/dev/null
  provedor=simulado
fi
echo "   provedor de pagamento: $provedor"

echo "== Rede e configuração"
# A lista de IPs do LB público é de quem está usando o ambiente
# (scripts/liberar-meu-ip.sh): o manifesto a declara fechada, e o deploy
# não deve trancar ninguém do lado de fora.
liberados=$(kubectl -n "$NS" get svc "$APP-publico" -o jsonpath='{.spec.loadBalancerSourceRanges}' 2>/dev/null || true)
kubectl apply -f "$RAIZ/k8s/10-configmap.yaml" -f "$RAIZ/k8s/40-service.yaml" -f "$RAIZ/k8s/45-service-publico.yaml"
if [ -n "$liberados" ] && [ "$liberados" != '["127.0.0.1/32"]' ]; then
  kubectl -n "$NS" patch svc "$APP-publico" --type merge -p "{\"spec\":{\"loadBalancerSourceRanges\":$liberados}}" >/dev/null
  echo "   IPs liberados no LB público preservados: $liberados"
fi
# O link do checkout simulado é aberto no navegador do cliente: aponta para
# o LB público deste serviço.
publico=$(endereco_lb "$APP-publico") || { echo "LB público sem endereço após 5 min." >&2; exit 1; }
kubectl -n "$NS" patch configmap "$CONFIGMAP" --type merge -p "$(
  "$PY" -c 'import json,sys; print(json.dumps({"data": {"AMBIENTE": sys.argv[1], "DYNAMODB_TABELA": sys.argv[2], "PAGAMENTO_PROVEDOR": sys.argv[3], "SIMULADOR_URL_BASE": sys.argv[4], "OTEL_SDK_DISABLED": sys.argv[5]}}))' \
    "$AMBIENTE" "$(ssm billing/tabela)" "$provedor" "http://${publico}" "$otel_desligado"
)" >/dev/null

echo "== Deployment"
# A imagem entra no manifesto antes do apply (um 'set image' depois faria um
# rollout com a imagem placeholder primeiro). A anotação com o hash da
# configuração reinicia os pods quando só o ConfigMap mudou.
hash_config=$(kubectl -n "$NS" get configmap "$CONFIGMAP" -o jsonpath='{.data}' | sha256sum | cut -c1-16)
sed -e "s|^\(\s*image:\s*\)oficina-billing-service:latest|\1${IMAGEM}|" \
    -e "0,/^      annotations:$/s//      annotations:\n        oficina.fiap\/configuracao: \"${hash_config}\"/" \
    "$RAIZ/k8s/30-deployment.yaml" >"$TMP/30-deployment.yaml"
grep -q "image: ${IMAGEM}$" "$TMP/30-deployment.yaml" || { echo "A imagem não entrou no manifesto." >&2; exit 1; }
kubectl apply -f "$TMP/30-deployment.yaml" -f "$RAIZ/k8s/50-hpa.yaml" -f "$RAIZ/k8s/60-pdb.yaml"
kubectl -n "$NS" rollout status "deployment/$APP" --timeout=600s

echo "== Smoke test"
kubectl -n "$NS" port-forward "svc/$APP" 18080:80 >/dev/null 2>&1 &
PF=$!
curl -fsS --retry 10 --retry-delay 3 --retry-all-errors http://localhost:18080/q/health/ready
echo

echo "== Endereços"
publico=$(endereco_lb "$APP-publico" || echo "(ainda provisionando)")
echo "   público: http://${publico}  (Swagger em /swagger)"

{
  echo "## Billing Service — \`${AMBIENTE}\`"
  echo
  echo "| | |"
  echo "| --- | --- |"
  echo "| Imagem | \`${IMAGEM}\` |"
  echo "| LB público (restrito por IP) | http://${publico}/swagger |"
  echo
  echo '```'
  kubectl -n "$NS" get deploy,pods,svc,hpa -l app.kubernetes.io/name="$APP" 2>/dev/null || true
  echo '```'
} >>"$RESUMO"
