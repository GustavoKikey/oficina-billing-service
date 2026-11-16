# Oficina — Billing Service

[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=GustavoKikey_oficina-billing-service&metric=alert_status)](https://sonarcloud.io/summary/overall?id=GustavoKikey_oficina-billing-service)
[![Cobertura](https://sonarcloud.io/api/project_badges/measure?project=GustavoKikey_oficina-billing-service&metric=coverage)](https://sonarcloud.io/component_measures?id=GustavoKikey_oficina-billing-service&metric=coverage)
[![CI](https://github.com/GustavoKikey/oficina-billing-service/actions/workflows/ci.yml/badge.svg)](https://github.com/GustavoKikey/oficina-billing-service/actions/workflows/ci.yml)
[![CD](https://github.com/GustavoKikey/oficina-billing-service/actions/workflows/cd.yml/badge.svg)](https://github.com/GustavoKikey/oficina-billing-service/actions/workflows/cd.yml)

Microsserviço de **orçamento e pagamento** da oficina mecânica (Tech Challenge FIAP
SOAT, **fase 4**). Gera o orçamento a partir dos itens do diagnóstico, cria a cobrança
no **Mercado Pago**, confirma o pagamento e faz o **estorno** quando a saga precisa
desfazer uma cobrança. É o serviço com banco **NoSQL** (DynamoDB) do sistema e é
participante da saga orquestrada pelo OS Service.

> **Situação:** domínio, saga, testes (unitários, integração), CI com SonarCloud e implantação na AWS (Terraform próprio e deploy contínuo no EKS) implementados.

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

## Arquitetura do serviço

Clean Architecture, como na fase 3: cada módulo de negócio tem `entities` (regras), `usecases` (fluxos), `gateways` (portas), `controllers`, `presenters` e `dtos`; tudo o que toca framework, banco ou rede fica em `external`, e o domínio não conhece Quarkus, banco nem RabbitMQ.

| Módulo | Responsabilidade |
| --- | --- |
| `orcamento` | Orçamento, cobrança, confirmação, expiração e estorno (participante da saga) |
| `shared` | Tipos de domínio comuns |
| `external` | Adaptadores: REST (`api`), DynamoDB com outbox transacional (`persistence`), RabbitMQ (`messaging`), Mercado Pago e simulador (`pagamento`), métricas |

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

## Qualidade e cobertura

| Onde | O quê |
| --- | --- |
| [SonarCloud](https://sonarcloud.io/summary/overall?id=GustavoKikey_oficina-billing-service) | Quality gate da `main` e de cada PR (badges no topo, atualizados a cada análise). Em 2026-11-16: cobertura **91,6%**, 0 bugs, 0 vulnerabilidades, 0 code smells |
| [Resumo de cada execução do CI](https://github.com/GustavoKikey/oficina-billing-service/actions/workflows/ci.yml) | Tabela de cobertura do JaCoCo (instruções, branches, linhas); relatório completo no artefato `relatorio-jacoco` |
| Build local | `./mvnw verify` falha abaixo de **80%**; relatório em `target/site/jacoco/index.html` |

## Mensageria

| Direção | Exchange / fila | Conteúdo |
| --- | --- | --- |
| Consome | `billing.comandos` ← `oficina.comandos` (`rk: billing`) | `GerarOrcamento`, `SolicitarPagamento`, `CancelarOrcamento`, `EstornarPagamento` |
| Publica | `oficina.eventos` (`rk: billing.*`) | `OrcamentoGerado`, `PagamentoConfirmado`, `PagamentoEstornado`, … |
| DLQ | `billing.comandos.dlq` | Mensagens rejeitadas após as retentativas |

## CI/CD

| Workflow | Quando | O quê |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | PR para a `main` e push na `main` | Build, testes, gate de cobertura, SonarCloud (o plano gratuito analisa só PRs e a `main`), imagem Docker, kubeconform |
| [`cd.yml`](.github/workflows/cd.yml) | CI **verde** num commit da `main` | Imagem no ECR e [`scripts/implantar.sh`](scripts/implantar.sh) no EKS (`hom`); `prod` por *Run workflow*. Com a sessão do Learner Lab fechada, pula com aviso |
| [`infra.yml`](.github/workflows/infra.yml) | PR que mexe em `infra/`; aplicar sob comando | fmt, validate e tflint; apply/destroy no *Run workflow* |

`main` protegida: merge só por Pull Request, com as três checagens do CI obrigatórias. O
`ci.yml` e o `cd.yml` são idênticos nos três serviços.

## Implantação na AWS

**Infraestrutura própria** ([`infra/`](infra/), Terraform): Tabela DynamoDB `oficina-billing-{env}` (sob demanda, GSI1 e GSI2 idênticos aos do `TabelaBilling.java`). Publica no SSM
(`/oficina/{env}/...`): `billing/tabela`. Depende só da plataforma
([oficina-infra-k8s](https://github.com/GustavoKikey/oficina-infra-k8s)), lida também pelo SSM.

**Deploy** ([`scripts/implantar.sh`](scripts/implantar.sh), chamado pelo `cd.yml` ou à mão):
cria os Secrets, aplica os manifestos de [`k8s/`](k8s/) com a imagem nova (os pods só
reiniciam quando a imagem ou a configuração mudam), espera o rollout e faz o smoke test.


| Secret no cluster | Origem | Para quê |
| --- | --- | --- |
| `oficina-jwt-publica` | SSM (`jwt/public-key`, do OS Service) | só a chave pública: este serviço valida, não emite |
| `oficina-billing-aws` | credencial da sessão do Learner Lab | acesso ao DynamoDB (no lab não há IRSA — [ADR 012](https://github.com/GustavoKikey/oficina-os-service/blob/main/docs/fase-4/adr/adr-012-implantacao-na-aws.md)) |
| `oficina-billing-mercadopago` | secret `MP_ACCESS_TOKEN` do repositório | opcional: Mercado Pago de verdade; sem ele, checkout simulado no LB público |
| `oficina-billing-rabbitmq` | plataforma (`oficina-infra-k8s`) | usuário do RabbitMQ |
| `oficina-newrelic` | secret `NEW_RELIC_LICENSE_KEY` do repositório | opcional: liga a telemetria |

Exposição: Service `LoadBalancer` público, que **nasce fechado** e é aberto para o IP de quem
vai usar (`scripts/liberar-meu-ip.sh` do oficina-infra-k8s); o Swagger fica em `/swagger`.

O ambiente inteiro (plataforma, bancos, deploy dos três serviços e Lambda) sobe com
`scripts/aplicar.sh hom` e cai com `scripts/destruir.sh hom`, no oficina-infra-k8s.
Decisões e custo: [ADR 012](https://github.com/GustavoKikey/oficina-os-service/blob/main/docs/fase-4/adr/adr-012-implantacao-na-aws.md).
