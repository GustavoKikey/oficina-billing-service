# =====================================================================
# Tabela do Billing Service — DynamoDB, tabela única (ADR 009)
# ---------------------------------------------------------------------
# Tem de ser idêntica à que TabelaBilling.java cria no DynamoDB Local
# (ambiente local e testes): mesmas chaves, índices e projeções. Na AWS
# o serviço roda com oficina.billing.criar-tabela=false e usa esta.
#
#   PK = OS#<osId>          SK = ORCAMENTO | <epoch>#<messageId> (outbox)
#   GSI1: orçamento pelo orcamentoId (external_reference do Mercado Pago)
#   GSI2 (esparso): pagamentos pendentes, em ordem de vencimento
# =====================================================================

locals {
  prefixo_ssm = "/${var.projeto}/${var.ambiente}"
  tabela      = "${var.projeto}-billing-${var.ambiente}"

  # O serviço só lê estes atributos pelos índices; o resto vem do item base.
  atributos_projetados = ["osId", "orcamentoId"]
}

resource "aws_dynamodb_table" "billing" {
  name = local.tabela
  # Sob demanda: paga por requisição, zero quando o lab está parado.
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "SK"
    type = "S"
  }
  attribute {
    name = "GSI1PK"
    type = "S"
  }
  attribute {
    name = "GSI1SK"
    type = "S"
  }
  attribute {
    name = "GSI2PK"
    type = "S"
  }
  attribute {
    name = "GSI2SK"
    type = "S"
  }

  global_secondary_index {
    name               = "GSI1"
    hash_key           = "GSI1PK"
    range_key          = "GSI1SK"
    projection_type    = "INCLUDE"
    non_key_attributes = local.atributos_projetados
  }

  global_secondary_index {
    name               = "GSI2"
    hash_key           = "GSI2PK"
    range_key          = "GSI2SK"
    projection_type    = "INCLUDE"
    non_key_attributes = local.atributos_projetados
  }

  server_side_encryption {
    enabled = true
  }

  deletion_protection_enabled = var.protecao_exclusao
}

resource "aws_ssm_parameter" "tabela" {
  name        = "${local.prefixo_ssm}/billing/tabela"
  description = "Nome da tabela DynamoDB do Billing Service"
  type        = "String"
  value       = aws_dynamodb_table.billing.name
  overwrite   = true
}
