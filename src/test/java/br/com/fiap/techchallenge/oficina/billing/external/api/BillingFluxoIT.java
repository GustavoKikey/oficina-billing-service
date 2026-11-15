package br.com.fiap.techchallenge.oficina.billing.external.api;

import br.com.fiap.techchallenge.oficina.billing.DynamoDbLocalResource;
import br.com.fiap.techchallenge.oficina.billing.suporte.MensageriaEmMemoria;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O Billing de ponta a ponta: comandos pela mensageria (conector em memória), DynamoDB
 * Local real, outbox e relay reais, provedor de pagamento simulado (sem credenciais).
 */
@QuarkusTest
@QuarkusTestResource(value = DynamoDbLocalResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = MensageriaEmMemoria.class, restrictToAnnotatedClass = true)
@TestSecurity(user = "admin", roles = "ADMINISTRADOR")
class BillingFluxoIT {

    private static final Duration ESPERA = Duration.ofSeconds(15);

    @Inject
    @Any
    InMemoryConnector conector;

    private InMemorySource<String> comandos;
    private InMemorySink<String> eventos;

    @BeforeEach
    void canais() {
        comandos = conector.source("comandos");
        eventos = conector.sink("eventos");
    }

    @Test
    @DisplayName("orçamento → cobrança → pagamento aprovado → estorno (compensação)")
    void orcamentoPagamentoEEstorno() {
        String os = UUID.randomUUID().toString();
        JsonObject gerado = gerarOrcamento(os);
        assertEquals("310.00", gerado.getJsonObject("payload").getString("valorTotal"));
        String orcamentoId = gerado.getJsonObject("payload").getString("orcamentoId");

        enviar(os, "SolicitarPagamento", new JsonObject().put("aprovadoPor", "CLIENTE")
                .put("aprovadoEm", OffsetDateTime.now(ZoneOffset.UTC).toString()));
        String link = esperar(os, "PagamentoSolicitado").getJsonObject("payload").getString("linkPagamento");
        assertTrue(link.endsWith("/simulador/checkout/" + orcamentoId));

        given().get("/simulador/checkout/" + orcamentoId).then().statusCode(200).body(containsString("Checkout simulado"));
        given().post("/simulador/pagamentos/" + orcamentoId + "?resultado=aprovado").then().statusCode(200);

        JsonObject confirmado = esperar(os, "PagamentoConfirmado");
        assertEquals("310.00", confirmado.getJsonObject("payload").getString("valorPago"));
        given().get("/orcamentos/os/" + os).then().statusCode(200)
                .body("status", equalTo("PAGO")).body("itens", hasSize(2))
                .body("pagamento.status", equalTo("APROVADO"));

        enviar(os, "EstornarPagamento", new JsonObject().put("motivo", "REPARO_NAO_LIBERADO"));
        JsonObject estornado = esperar(os, "PagamentoEstornado");
        assertEquals("310.00", estornado.getJsonObject("payload").getString("valorEstornado"));
        given().get("/orcamentos/os/" + os).then().body("status", equalTo("ESTORNADO"))
                .body("pagamento.status", equalTo("ESTORNADO"));

        String metricas = given().get("/q/metrics").then().extract().asString();
        assertTrue(metricas.contains("oficina_pagamentos_total{status=\"CONFIRMADO\"}"));
    }

    @Test
    @DisplayName("cartão recusado vira PagamentoRecusado; a compensação cancela o orçamento")
    void pagamentoRecusadoECancelamento() {
        String os = UUID.randomUUID().toString();
        String orcamentoId = gerarOrcamento(os).getJsonObject("payload").getString("orcamentoId");
        enviar(os, "SolicitarPagamento", new JsonObject().put("aprovadoPor", "ATENDENTE"));
        esperar(os, "PagamentoSolicitado");

        given().post("/simulador/pagamentos/" + orcamentoId + "?resultado=recusado").then().statusCode(200);
        esperar(os, "PagamentoRecusado");

        enviar(os, "CancelarOrcamento", new JsonObject().put("motivo", "PAGAMENTO_RECUSADO"));
        esperar(os, "OrcamentoCancelado");
        given().get("/orcamentos/os/" + os).then().body("status", equalTo("CANCELADO"))
                .body("motivoCancelamento", equalTo("PAGAMENTO_RECUSADO"));
    }

    @Test
    @DisplayName("comando repetido responde de novo; itens inválidos viram OrcamentoNaoGerado")
    void idempotenciaEOrcamentoInvalido() {
        String os = UUID.randomUUID().toString();
        String id = gerarOrcamento(os).getJsonObject("payload").getString("orcamentoId");

        enviar(os, "GerarOrcamento", payloadGerar());
        await().atMost(ESPERA).until(() -> eventosDa(os, "OrcamentoGerado").size() == 2);
        assertEquals(id, eventosDa(os, "OrcamentoGerado").get(1).getJsonObject("payload").getString("orcamentoId"));

        String outra = UUID.randomUUID().toString();
        enviar(outra, "GerarOrcamento", new JsonObject().put("itens", new JsonArray()));
        esperar(outra, "OrcamentoNaoGerado");
        given().get("/orcamentos/os/" + outra).then().statusCode(404);
    }

    @Test
    @DisplayName("webhook: assinatura inválida é recusada; válida dispara a consulta do pagamento")
    void webhook() {
        given().queryParam("data.id", "123").queryParam("type", "payment")
                .header("x-signature", "ts=1,v1=errado").header("x-request-id", "r1")
                .post("/webhooks/mercadopago").then().statusCode(401);

        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String manifesto = "id:123;request-id:r1;ts:" + ts + ";";
        given().queryParam("data.id", "123").queryParam("type", "payment")
                .header("x-signature", "ts=" + ts + ",v1=" + hmac("segredo-de-teste", manifesto))
                .header("x-request-id", "r1")
                .post("/webhooks/mercadopago").then().statusCode(200);

        given().get("/simulador/checkout/" + UUID.randomUUID()).then().statusCode(404);
    }

    // ---------- apoio ----------

    private static JsonObject payloadGerar() {
        return new JsonObject()
                .put("cliente", new JsonObject().put("id", "c1").put("nome", "Alice").put("email", "alice@exemplo.com"))
                .put("itens", new JsonArray()
                        .add(new JsonObject().put("tipo", "SERVICO").put("referenciaId", "s1").put("descricao", "Alinhamento")
                                .put("quantidade", 1).put("valorUnitario", "120.00"))
                        .add(new JsonObject().put("tipo", "PECA").put("referenciaId", "p1").put("descricao", "Pastilha")
                                .put("quantidade", 2).put("valorUnitario", "95.00")));
    }

    private JsonObject gerarOrcamento(String os) {
        enviar(os, "GerarOrcamento", payloadGerar());
        return esperar(os, "OrcamentoGerado");
    }

    private void enviar(String osId, String tipo, JsonObject payload) {
        comandos.send(new JsonObject()
                .put("messageId", UUID.randomUUID().toString())
                .put("type", tipo)
                .put("version", 1)
                .put("correlationId", osId)
                .put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
                .put("source", "os-service")
                .put("payload", payload.put("osId", osId))
                .encode());
    }

    private List<JsonObject> eventosDa(String osId, String tipo) {
        return eventos.received().stream().map(Message::getPayload).map(JsonObject::new)
                .filter(e -> osId.equals(e.getString("correlationId")) && tipo.equals(e.getString("type")))
                .toList();
    }

    private JsonObject esperar(String osId, String tipo) {
        await().atMost(ESPERA).until(() -> !eventosDa(osId, tipo).isEmpty());
        JsonObject e = eventosDa(osId, tipo).get(0);
        assertEquals("billing-service", e.getString("source"));
        return e;
    }

    private static String hmac(String segredo, String mensagem) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(mensagem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
