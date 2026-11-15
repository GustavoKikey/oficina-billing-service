package br.com.fiap.techchallenge.oficina.billing.external.api;

import br.com.fiap.techchallenge.oficina.billing.external.pagamento.AssinaturaWebhook;
import br.com.fiap.techchallenge.oficina.billing.orcamento.controllers.BillingController;
import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Webhook do Mercado Pago — a única rota sem JWT (arquitetura.md §6). Autenticada pela
 * assinatura; mesmo válida, só dispara a consulta do pagamento na API (RFC 004 §3.2).
 *
 * <p>Responde 200 depressa para o Mercado Pago não reenviar; o trabalho é a consulta, que
 * também acontece pela verificação periódica se esta falhar.
 */
@Path("/webhooks/mercadopago")
@PermitAll
@Tag(name = "Webhooks")
public class WebhookMercadoPagoResource {

    private static final Logger LOG = Logger.getLogger(WebhookMercadoPagoResource.class);

    private final BillingController controller;
    private final MetricasGateway metricas;
    private final Optional<String> segredo;

    public WebhookMercadoPagoResource(BillingController controller, MetricasGateway metricas,
                                      @ConfigProperty(name = "oficina.mercadopago.webhook-secret") Optional<String> segredo) {
        this.controller = controller;
        this.metricas = metricas;
        this.segredo = segredo;
    }

    @POST
    @Operation(summary = "Notificação do Mercado Pago (assinada) — dispara a consulta do pagamento")
    public Response receber(@QueryParam("data.id") String dataId, @QueryParam("type") String tipo,
                            @HeaderParam("x-signature") String assinatura,
                            @HeaderParam("x-request-id") String requestId) {
        if (!AssinaturaWebhook.valida(segredo.orElse(null), assinatura, requestId, dataId)) {
            metricas.falhaIntegracao("mercadopago-webhook-assinatura");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (dataId == null || (tipo != null && !"payment".equals(tipo))) {
            return Response.ok().build();   // outros tópicos: nada a fazer
        }
        try {
            boolean mudou = controller.notificacaoDePagamento(dataId);
            LOG.infof("Webhook do pagamento %s processado (mudou: %s)", dataId, mudou);
        } catch (RuntimeException e) {
            // A consulta periódica cobre: confirmar o recebimento evita reenvio em rajada.
            metricas.falhaIntegracao("mercadopago-webhook");
            LOG.warnf("Webhook do pagamento %s falhou (%s) — a consulta periódica cobre", dataId, e.getMessage());
        }
        return Response.ok().build();
    }
}
