package br.com.fiap.techchallenge.oficina.billing.external.persistence;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamoDbHealthCheckTest {

    @Test
    @SuppressWarnings("unchecked")
    void ficaDownQuandoOBancoNaoResponde() {
        DynamoDbClient cliente = mock(DynamoDbClient.class);
        when(cliente.listTables(any(Consumer.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: Connection refused"));

        HealthCheckResponse resposta = new DynamoDbHealthCheck(cliente).call();

        assertEquals(HealthCheckResponse.Status.DOWN, resposta.getStatus());
        assertEquals(DynamoDbHealthCheck.NOME, resposta.getName());
        assertTrue(resposta.getData().orElseThrow().get("erro").toString().contains("Connection refused"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ficaUpQuandoOBancoResponde() {
        DynamoDbClient cliente = mock(DynamoDbClient.class);
        when(cliente.listTables(any(Consumer.class))).thenReturn(null);

        assertEquals(HealthCheckResponse.Status.UP, new DynamoDbHealthCheck(cliente).call().getStatus());
    }
}
