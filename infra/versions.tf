# =====================================================================
# Infraestrutura do Billing Service (fase 4)
# ---------------------------------------------------------------------
# O que é só deste serviço: a tabela DynamoDB (o NoSQL do sistema,
# ADR 009). Não depende de rede: o DynamoDB é serviço regional, e os pods
# chegam nele pela internet do próprio nó.
#
# Ordem: oficina-infra-k8s -> [este] -> deploy (cd.yml)
# =====================================================================
terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }

  # Mesmo bucket de state da plataforma; a chave
  # (billing-service/<ambiente>/...) chega por -backend-config no init.
  backend "s3" {
    region  = "us-east-1"
    encrypt = true
  }
}

provider "aws" {
  region = var.regiao

  default_tags {
    tags = {
      Projeto   = var.projeto
      Ambiente  = var.ambiente
      Servico   = "billing-service"
      ManagedBy = "terraform"
    }
  }
}
