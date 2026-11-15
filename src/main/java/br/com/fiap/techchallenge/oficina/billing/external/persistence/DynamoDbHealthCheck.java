package br.com.fiap.techchallenge.oficina.billing.external.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Readiness do banco do Billing. A extensão do DynamoDB não traz health check próprio
 * (ao contrário do datasource JDBC dos outros serviços), então sem este o pod ficaria
 * "pronto" mesmo sem alcançar o banco — e receberia comandos que não conseguiria gravar.
 *
 * <p>{@code ListTables} com limite 1 é a chamada mais barata que prova rede, credencial
 * e região corretas.
 */
@Readiness
@ApplicationScoped
public class DynamoDbHealthCheck implements HealthCheck {

    static final String NOME = "DynamoDB";

    private final DynamoDbClient dynamoDb;

    public DynamoDbHealthCheck(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    @Override
    public HealthCheckResponse call() {
        try {
            dynamoDb.listTables(requisicao -> requisicao.limit(1));
            return HealthCheckResponse.up(NOME);
        } catch (RuntimeException e) {
            return HealthCheckResponse.named(NOME)
                    .down()
                    .withData("erro", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
