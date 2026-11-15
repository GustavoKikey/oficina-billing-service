# Oficina — Billing Service

Microsserviço de **orçamento e pagamento** da oficina mecânica (Tech Challenge FIAP
SOAT, **fase 4**). Gera o orçamento a partir dos itens do diagnóstico, cria a cobrança
no **Mercado Pago**, confirma o pagamento e faz o **estorno** quando a saga precisa
desfazer uma cobrança. É o serviço com banco **NoSQL** (DynamoDB) do sistema e é
participante da saga orquestrada pelo OS Service.

> **Situação: esqueleto (etapa 2 de 5).** Projeto, configuração, mensageria, health
> check do DynamoDB, CI, manifestos e ambiente local estão prontos e testados. O
> domínio e a integração com o Mercado Pago entram na etapa 3.

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

[`ci.yml`](.github/workflows/ci.yml) em todo push fora da `main` e em PRs: build,
testes, gate de cobertura, SonarCloud, imagem Docker e kubeconform. Deploy no EKS
(`homolog` → `hom`, `main` → `prod`) na etapa 5. `main` protegida, merge só por PR.
