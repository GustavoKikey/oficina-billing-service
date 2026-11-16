output "tabela" {
  description = "Nome da tabela DynamoDB do Billing Service."
  value       = aws_dynamodb_table.billing.name
}

output "tabela_arn" {
  description = "ARN da tabela."
  value       = aws_dynamodb_table.billing.arn
}
