package br.com.fiap.techchallenge.oficina.billing;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Sobe o serviço inteiro contra DynamoDB Local e RabbitMQ reais e confere que ele está
 * pronto: banco alcançável e conector de mensageria ligado à fila {@code billing.comandos}.
 */
@QuarkusTest
@QuarkusTestResource(DynamoDbLocalResource.class)
class SaudeTest {

    @Test
    void servicoFicaProntoComBancoEMensageria() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItems(
                        "DynamoDB",
                        "SmallRye Reactive Messaging - readiness check"));
    }

    @Test
    void publicaContratoOpenApi() {
        given()
                .when().get("/openapi")
                .then()
                .statusCode(200);
    }
}
