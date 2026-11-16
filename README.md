# Oficina — Billing Service

Microsserviço de **orçamento e pagamento** da oficina mecânica (Tech Challenge FIAP
SOAT, **fase 4**). Gera o orçamento a partir dos itens do diagnóstico, cria a cobrança
no **Mercado Pago**, confirma o pagamento e faz o **estorno** quando a saga precisa
desfazer uma cobrança. É o serviço com banco **NoSQL** (DynamoDB) do sistema e é
participante da saga orquestrada pelo OS Service.

> **Situação: domínio e participação na saga implementados (etapa 3 de 5).** Deploy no
> EKS, tabela via Terraform e README final entram na etapa 5.

## O que este serviço faz

| Comando da saga | O que acontece | Resposta |
| --- | --- | --- |
| `GerarOrcamento` | Calcula o valor a partir dos itens do diagnóstico | `OrcamentoGerado` ou `OrcamentoNaoGerado` |
| `SolicitarPagamento` | Cliente aprovou: cria a cobrança no Mercado Pago (Checkout Pro) | `PagamentoSolicitado` (com o link) |
| — (consulta periódica / webhook) | Confere o pagamento **na API** do provedor | `PagamentoConfirmado`, `PagamentoRecusado` ou `PagamentoExpirado` |
| `CancelarOrcamento` (compensação) | Cancela; se já foi pago, estorna | `OrcamentoCancelado` |
| `EstornarPagamento` (compensação) | Estorno total, com retentativa | `PagamentoEstornado` ou `EstornoFalhou` |

Garantias: idempotência pela chave de negócio (um orçamento por OS), estado e evento
gravados juntos (`TransactWriteItems`), e **orçamento cancelado nunca fica com dinheiro**
(pagamento aprovado depois do cancelamento é estornado automaticamente).

### Provedor de pagamento

| `PAGAMENTO_PROVEDOR` | Uso |
| --- | --- |
| `simulado` (padrão) | Sem credenciais: o link aponta para `/simulador/checkout/{orcamentoId}` e o pagamento é aprovado ou recusado com `POST /simulador/pagamentos/{orcamentoId}?resultado=aprovado\|recusado`. Ambiente local, testes e ensaio. |
| `mercadopago` | Mercado Pago real (sandbox): `MP_ACCESS_TOKEN`, `MP_WEBHOOK_SECRET` e, se houver URL pública, `MP_NOTIFICATION_URL`. |

O fluxo depois da cobrança (consulta periódica, eventos, estorno) é o mesmo código nos dois.

---

## Documentação da arquitetura

A arquitetura do sistema inteiro está no repositório do orquestrador:
[oficina-os-service/docs/fase-4](https://github.com/GustavoKikey/oficina-os-service/tree/main/docs/fase-4).
Específicos deste serviço:

- [ADR 009](https://github.com/GustavoKikey/oficina-os-service/blob/main/docs/fase-4/adr/adr-009-dynamodb-no-billing.md):
  por que DynamoDB e o modelo de tabela única
- [RFC 004](https://github.com/GustavoKikey/oficina-os-service/blob/main/docs/fase-4/rfc/rfc-004-integracao-mercado-pago.md):
  integração com o Mercado Pago (Checkout Pro, consulta periódica + webhook, estorno)

## Stack

Java 21 · Quarkus 3.40 (LTS) · **Amazon DynamoDB** · RabbitMQ (SmallRye Reactive
Messaging) · REST Client (API do Mercado Pago) · JWT RS256 (só validação) ·
OpenTelemetry + Micrometer + logs JSON (New Relic) · JUnit 5, REST-Assured,
Testcontainers · JaCoCo (gate de 80%) · SonarCloud · Docker · Kubernetes (EKS)

## Executar localmente

Pré-requisitos: Docker e JDK 21. O Maven vem pelo wrapper (`./mvnw`).

O banco local é o **DynamoDB Local** oficial da AWS, e não o Dev Services do Quarkus,
que depende do LocalStack (agora exige token de conta). Para o modo dev, suba o
DynamoDB pelo compose do OS Service. O RabbitMQ o Quarkus sobe sozinho:

```bash
# na pasta oficina-os-service
docker compose up -d dynamodb

# nesta pasta
./mvnw quarkus:dev
```

No sistema completo (`docker compose up -d --build` no OS Service), este serviço
responde em http://localhost:8082/swagger.

## Testes

```bash
./mvnw verify
```

Os testes que sobem a aplicação usam DynamoDB Local e RabbitMQ reais (Testcontainers).
O build **falha se a cobertura do serviço ficar abaixo de 80%**.

## Mensageria

| Direção | Exchange / fila | Conteúdo |
| --- | --- | --- |
| Consome | `billing.comandos` ← `oficina.comandos` (`rk: billing`) | `GerarOrcamento`, `SolicitarPagamento`, `CancelarOrcamento`, `EstornarPagamento` |
| Publica | `oficina.eventos` (`rk: billing.*`) | `OrcamentoGerado`, `PagamentoConfirmado`, `PagamentoEstornado`, … |
| DLQ | `billing.comandos.dlq` | Mensagens rejeitadas após as retentativas |

## CI/CD

[`ci.yml`](.github/workflows/ci.yml) em todo PR para a `main` e na `main` depois do merge:
build, testes, gate de cobertura, SonarCloud (o plano gratuito analisa só PRs e a `main`),
imagem Docker e kubeconform. Numa branch sem PR, rode à mão (*Run workflow*). Deploy no EKS
(`homolog` → `hom`, `main` → `prod`) na etapa 5. `main` protegida, merge só por PR.
