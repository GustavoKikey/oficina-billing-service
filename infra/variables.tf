variable "ambiente" {
  description = "Ambiente lógico (hom | prod). Prefixa os parâmetros no SSM e o nome da tabela."
  type        = string

  validation {
    condition     = contains(["hom", "prod"], var.ambiente)
    error_message = "Ambiente deve ser 'hom' ou 'prod'."
  }
}

variable "regiao" {
  description = "Região AWS. O Learner Lab só libera us-east-1."
  type        = string
  default     = "us-east-1"
}

variable "projeto" {
  description = "Nome do projeto, usado em nomes de recurso e no path do SSM."
  type        = string
  default     = "oficina"
}

variable "protecao_exclusao" {
  description = "Deletion protection da tabela. Desligada: o ambiente do lab é destruído ao fim de cada sessão."
  type        = bool
  default     = false
}
