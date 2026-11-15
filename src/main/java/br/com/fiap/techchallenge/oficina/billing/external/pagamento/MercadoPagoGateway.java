package br.com.fiap.techchallenge.oficina.billing.external.pagamento;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.CobrancaCriada;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter do Mercado Pago — Checkout Pro (RFC 004).
 *
 * <ul>
 *   <li>cobrança: {@code POST /checkout/preferences} com {@code external_reference} = id do
 *       orçamento e expiração;</li>
 *   <li>consulta: {@code GET /v1/payments/search?external_reference=} e
 *       {@code GET /v1/payments/{id}};</li>
 *   <li>estorno total: {@code POST /v1/payments/{id}/refunds} com {@code X-Idempotency-Key}.</li>
 * </ul>
 * HTTP do JDK em vez de um cliente gerado: poucas chamadas, testáveis contra um servidor
 * HTTP local, sem depender do runtime do Quarkus.
 */
public class MercadoPagoGateway implements ProvedorPagamentoGateway {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter DATA_MP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI base;
    private final String accessToken;
    private final String notificationUrl;
    private final String urlRetorno;
    private final boolean linkSandbox;

    public MercadoPagoGateway(HttpClient http, ObjectMapper json, URI base, String accessToken,
                              String notificationUrl, String urlRetorno, boolean linkSandbox) {
        this.http = http;
        this.json = json;
        this.base = base;
        this.accessToken = accessToken;
        this.notificationUrl = notificationUrl;
        this.urlRetorno = urlRetorno;
        this.linkSandbox = linkSandbox;
    }

    @Override
    public CobrancaCriada criarCobranca(Orcamento orcamento, OffsetDateTime expiraEm) {
        List<Map<String, Object>> itens = new ArrayList<>();
        for (Orcamento.Item i : orcamento.itens()) {
            itens.add(Map.of("id", i.referenciaId(), "title", i.descricao(), "quantity", i.quantidade(),
                    "unit_price", i.valorUnitario().valor(), "currency_id", "BRL"));
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("items", itens);
        corpo.put("external_reference", orcamento.id().toString());
        corpo.put("statement_descriptor", "OFICINA");
        corpo.put("expires", true);
        corpo.put("expiration_date_to", expiraEm.withOffsetSameInstant(ZoneOffset.UTC).format(DATA_MP));
        if (orcamento.cliente() != null && orcamento.cliente().email() != null) {
            corpo.put("payer", Map.of("email", orcamento.cliente().email()));
        }
        if (notificationUrl != null && !notificationUrl.isBlank()) {
            corpo.put("notification_url", notificationUrl);
        }
        if (urlRetorno != null && !urlRetorno.isBlank()) {
            corpo.put("back_urls", Map.of("success", urlRetorno, "failure", urlRetorno, "pending", urlRetorno));
        }

        JsonNode resposta = enviar(requisicao("/checkout/preferences").POST(corpo(corpo)).build(), "criar preferência");
        String link = resposta.path(linkSandbox ? "sandbox_init_point" : "init_point").asText(null);
        if (link == null) {
            link = resposta.path("init_point").asText();
        }
        return new CobrancaCriada(resposta.path("id").asText(), link, expiraEm);
    }

    @Override
    public Optional<SituacaoPagamento> consultarPorOrcamento(Orcamento orcamento) {
        String ref = URLEncoder.encode(orcamento.id().toString(), StandardCharsets.UTF_8);
        JsonNode resposta = enviar(requisicao("/v1/payments/search?sort=date_created&criteria=desc&external_reference="
                + ref).GET().build(), "buscar pagamentos");
        JsonNode escolhido = null;
        for (JsonNode p : resposta.path("results")) {
            if ("approved".equals(p.path("status").asText())) {
                escolhido = p;   // aprovado vence: é o único que muda o destino da OS
                break;
            }
            if (escolhido == null) {
                escolhido = p;   // senão, o mais recente
            }
        }
        return Optional.ofNullable(escolhido).map(MercadoPagoGateway::traduzir);
    }

    @Override
    public Optional<SituacaoPagamento> consultarPagamento(String idPagamento) {
        HttpRequest req = requisicao("/v1/payments/" + URLEncoder.encode(idPagamento, StandardCharsets.UTF_8))
                .GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return Optional.empty();
            }
            return Optional.of(traduzir(ler(resp, "consultar pagamento")));
        } catch (IOException e) {
            throw new FalhaProvedorException("Mercado Pago indisponível ao consultar pagamento: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaProvedorException("Consulta ao Mercado Pago interrompida", e);
        }
    }

    @Override
    public String estornar(String idPagamento, String chaveIdempotencia) {
        HttpRequest req = requisicao("/v1/payments/" + URLEncoder.encode(idPagamento, StandardCharsets.UTF_8) + "/refunds")
                .header("X-Idempotency-Key", chaveIdempotencia)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return enviar(req, "estornar").path("id").asText();
    }

    // ---------------------------------------------------------------------

    static SituacaoPagamento traduzir(JsonNode p) {
        SituacaoPagamento.Situacao situacao = switch (p.path("status").asText()) {
            case "approved" -> SituacaoPagamento.Situacao.APROVADO;
            case "rejected", "cancelled" -> SituacaoPagamento.Situacao.RECUSADO;
            default -> SituacaoPagamento.Situacao.PENDENTE; // pending, in_process, authorized
        };
        String quando = p.path("date_approved").asText(null);
        if (quando == null || quando.isBlank()) {
            quando = p.path("date_last_updated").asText(null);
        }
        OffsetDateTime instante = quando == null || quando.isBlank()
                ? OffsetDateTime.now(ZoneOffset.UTC) : OffsetDateTime.parse(quando);
        JsonNode valor = p.get("transaction_amount");
        return new SituacaoPagamento(p.path("id").asText(), p.path("external_reference").asText(null), situacao,
                p.path("status_detail").asText(null),
                valor == null || valor.isNull() ? null : Dinheiro.de(valor.decimalValue()), instante);
    }

    private HttpRequest.Builder requisicao(String caminho) {
        return HttpRequest.newBuilder(base.resolve(caminho))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json");
    }

    private HttpRequest.BodyPublisher corpo(Object valor) {
        try {
            return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(valor));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode enviar(HttpRequest req, String operacao) {
        try {
            return ler(http.send(req, HttpResponse.BodyHandlers.ofString()), operacao);
        } catch (IOException e) {
            throw new FalhaProvedorException("Mercado Pago indisponível ao " + operacao + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaProvedorException("Chamada ao Mercado Pago interrompida", e);
        }
    }

    private JsonNode ler(HttpResponse<String> resp, String operacao) throws IOException {
        if (resp.statusCode() / 100 != 2) {
            throw new FalhaProvedorException("Mercado Pago respondeu " + resp.statusCode() + " ao " + operacao
                    + ": " + resumoDoErro(resp.body()));
        }
        return json.readTree(resp.body());
    }

    /** O "message" do corpo de erro, que é o que interessa no alerta; senão, o começo do corpo. */
    private String resumoDoErro(String corpo) {
        try {
            String mensagem = json.readTree(corpo).path("message").asText("");
            if (!mensagem.isBlank()) {
                return mensagem;
            }
        } catch (IOException | RuntimeException corpoNaoEhJson) {
            // segue com o texto cru
        }
        String texto = corpo == null ? "" : corpo.strip();
        return texto.length() > 200 ? texto.substring(0, 200) + "…" : texto;
    }
}
