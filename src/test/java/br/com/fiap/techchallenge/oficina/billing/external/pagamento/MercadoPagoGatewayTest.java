package br.com.fiap.techchallenge.oficina.billing.external.pagamento;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.CobrancaCriada;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static br.com.fiap.techchallenge.oficina.billing.suporte.Fakes.AGORA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O adapter contra um "Mercado Pago" local (servidor HTTP do JDK): confere o que é enviado
 * (rotas, autenticação, idempotência, corpo) e a tradução das respostas. O CI nunca chama
 * o Mercado Pago de verdade (RFC 004 §6).
 */
class MercadoPagoGatewayTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Map<String, String> respostas = new ConcurrentHashMap<>();
    private final Map<String, Integer> status = new ConcurrentHashMap<>();
    private final Map<String, String> recebidos = new ConcurrentHashMap<>();
    private final Map<String, String> cabecalhos = new ConcurrentHashMap<>();
    private HttpServer servidor;
    private URI base;
    private MercadoPagoGateway gateway;
    private Orcamento orcamento;

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", this::responder);
        servidor.start();
        base = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
        gateway = new MercadoPagoGateway(HttpClient.newHttpClient(), json, base, "TEST-token",
                "https://billing.test/webhooks/mercadopago", "https://oficina.test/status", true);
        orcamento = Orcamento.gerar(OrdemServicoId.novo(), new Orcamento.Cliente("c", "Alice", "alice@exemplo.com"),
                List.of(new Orcamento.Item("PECA", "p1", "Pastilha", 2, Dinheiro.de("95.00"))), AGORA);
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
    }

    private void responder(HttpExchange troca) throws IOException {
        String chave = troca.getRequestMethod() + " " + troca.getRequestURI().getPath();
        recebidos.put(chave, new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        cabecalhos.put(chave + " auth", String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
        cabecalhos.put(chave + " idem", String.valueOf(troca.getRequestHeaders().getFirst("X-Idempotency-Key")));
        cabecalhos.put(chave + " query", String.valueOf(troca.getRequestURI().getQuery()));
        byte[] corpo = respostas.getOrDefault(chave, "{}").getBytes(StandardCharsets.UTF_8);
        troca.sendResponseHeaders(status.getOrDefault(chave, 200), corpo.length);
        troca.getResponseBody().write(corpo);
        troca.close();
    }

    @Test
    void criaPreferenciaComReferenciaExpiracaoENotificacao() throws IOException {
        respostas.put("POST /checkout/preferences", """
                {"id": "pref-1", "init_point": "https://mp/prod", "sandbox_init_point": "https://mp/sandbox"}""");

        CobrancaCriada cobranca = gateway.criarCobranca(orcamento, AGORA.plusMinutes(30));

        assertEquals("pref-1", cobranca.referencia());
        assertEquals("https://mp/sandbox", cobranca.link(), "credencial de teste usa o link de sandbox");
        JsonNode enviado = json.readTree(recebidos.get("POST /checkout/preferences"));
        assertEquals(orcamento.id().toString(), enviado.get("external_reference").asText());
        assertTrue(enviado.get("expires").asBoolean());
        assertEquals("2026-11-15T14:30:00.000Z", enviado.get("expiration_date_to").asText());
        assertEquals(95.0, enviado.get("items").get(0).get("unit_price").asDouble());
        assertEquals(2, enviado.get("items").get(0).get("quantity").asInt());
        assertEquals("https://billing.test/webhooks/mercadopago", enviado.get("notification_url").asText());
        assertEquals("Bearer TEST-token", cabecalhos.get("POST /checkout/preferences auth"));
    }

    @Test
    void consultaPeloOrcamentoPrefereOAprovado() {
        respostas.put("GET /v1/payments/search", """
                {"results": [
                  {"id": 11, "status": "rejected", "status_detail": "cc_rejected_other_reason",
                   "external_reference": "x", "transaction_amount": 190.0, "date_last_updated": "2026-11-15T14:05:00.000-04:00"},
                  {"id": 12, "status": "approved", "status_detail": "accredited",
                   "external_reference": "x", "transaction_amount": 190.0, "date_approved": "2026-11-15T14:06:00.000-04:00"}
                ]}""");

        SituacaoPagamento s = gateway.consultarPorOrcamento(orcamento).orElseThrow();

        assertEquals("12", s.idPagamento());
        assertEquals(SituacaoPagamento.Situacao.APROVADO, s.situacao());
        assertEquals(Dinheiro.de("190.00"), s.valor());
        assertTrue(cabecalhos.get("GET /v1/payments/search query").contains("external_reference=" + orcamento.id()));
    }

    @Test
    void semPagamentosOuPendente() {
        respostas.put("GET /v1/payments/search", "{\"results\": []}");
        assertTrue(gateway.consultarPorOrcamento(orcamento).isEmpty());

        respostas.put("GET /v1/payments/search", """
                {"results": [{"id": 13, "status": "in_process", "external_reference": "x"}]}""");
        assertEquals(SituacaoPagamento.Situacao.PENDENTE, gateway.consultarPorOrcamento(orcamento).orElseThrow().situacao());
    }

    @Test
    void consultaPorIdDoWebhook() {
        respostas.put("GET /v1/payments/77", """
                {"id": 77, "status": "cancelled", "external_reference": "orc-1", "date_last_updated": "2026-11-15T14:00:00Z"}""");
        SituacaoPagamento s = gateway.consultarPagamento("77").orElseThrow();
        assertEquals(SituacaoPagamento.Situacao.RECUSADO, s.situacao());
        assertEquals("orc-1", s.referenciaExterna());

        status.put("GET /v1/payments/78", 404);
        assertTrue(gateway.consultarPagamento("78").isEmpty());
    }

    @Test
    void estornoTotalComChaveDeIdempotencia() {
        respostas.put("POST /v1/payments/12/refunds", "{\"id\": 999, \"status\": \"approved\"}");

        assertEquals("999", gateway.estornar("12", "estorno-orc-1"));
        assertEquals("estorno-orc-1", cabecalhos.get("POST /v1/payments/12/refunds idem"));
    }

    @Test
    void erroDoProvedorViraFalhaTecnica() {
        status.put("POST /v1/payments/12/refunds", 503);
        respostas.put("POST /v1/payments/12/refunds", "{\"message\": \"indisponível\"}");

        var erro = assertThrows(ProvedorPagamentoGateway.FalhaProvedorException.class,
                () -> gateway.estornar("12", "k"));
        assertTrue(erro.getMessage().contains("503"));

        servidor.stop(0);
        assertThrows(ProvedorPagamentoGateway.FalhaProvedorException.class,
                () -> gateway.criarCobranca(orcamento, AGORA));
    }

    @Test
    void preferenciaMinimaComCredencialDeProducao() throws IOException {
        var producao = new MercadoPagoGateway(HttpClient.newHttpClient(), json, base, "APP_USR-token", null, " ", false);
        var semEmail = Orcamento.gerar(OrdemServicoId.novo(), new Orcamento.Cliente("c", "Bia", null),
                List.of(new Orcamento.Item("SERVICO", "s1", "Alinhamento", 1, Dinheiro.de("120.00"))), AGORA);
        respostas.put("POST /checkout/preferences", """
                {"id": "pref-2", "init_point": "https://mp/prod", "sandbox_init_point": "https://mp/sandbox"}""");

        assertEquals("https://mp/prod", producao.criarCobranca(semEmail, AGORA.plusMinutes(30)).link());

        JsonNode enviado = json.readTree(recebidos.get("POST /checkout/preferences"));
        assertFalse(enviado.has("payer"), "sem e-mail não manda pagador");
        assertFalse(enviado.has("notification_url"), "sem rota pública: só a consulta periódica");
        assertFalse(enviado.has("back_urls"));
    }

    @Test
    void semClienteESemLinkDeSandboxUsaOLinkPrincipal() {
        var semCliente = Orcamento.gerar(OrdemServicoId.novo(), null,
                List.of(new Orcamento.Item("PECA", "p1", "Pastilha", 1, Dinheiro.de("95.00"))), AGORA);
        respostas.put("POST /checkout/preferences", "{\"id\": \"pref-3\", \"init_point\": \"https://mp/prod\"}");

        assertEquals("https://mp/prod", gateway.criarCobranca(semCliente, AGORA.plusMinutes(30)).link());
    }

    @Test
    void pagamentoSemDatasNemValor() {
        respostas.put("GET /v1/payments/79", "{\"id\": 79, \"status\": \"authorized\", \"transaction_amount\": null}");

        SituacaoPagamento s = gateway.consultarPagamento("79").orElseThrow();

        assertEquals(SituacaoPagamento.Situacao.PENDENTE, s.situacao());
        assertNull(s.valor());
        assertNotNull(s.quando(), "sem data do provedor, vale o momento da consulta");
    }
}
