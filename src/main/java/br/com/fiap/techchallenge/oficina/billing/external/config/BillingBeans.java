package br.com.fiap.techchallenge.oficina.billing.external.config;

import br.com.fiap.techchallenge.oficina.billing.external.pagamento.MercadoPagoGateway;
import br.com.fiap.techchallenge.oficina.billing.external.pagamento.ProvedorSimulado;
import br.com.fiap.techchallenge.oficina.billing.orcamento.controllers.BillingController;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.OrcamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.orcamento.usecases.AcompanharPagamentosUseCase;
import br.com.fiap.techchallenge.oficina.billing.orcamento.usecases.ComandosBillingUseCase;
import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class BillingBeans {

    private static final Logger LOG = Logger.getLogger(BillingBeans.class);

    /**
     * {@code PAGAMENTO_PROVEDOR=mercadopago} usa o Mercado Pago (exige {@code MP_ACCESS_TOKEN});
     * {@code simulado} (padrão) dispensa credenciais — ambiente local, testes e ensaio.
     */
    @Produces
    @Singleton
    ProvedorPagamentoGateway provedor(
            ObjectMapper json, Clock relogio,
            @ConfigProperty(name = "oficina.pagamento.provedor", defaultValue = "simulado") String tipo,
            @ConfigProperty(name = "oficina.mercadopago.base-url", defaultValue = "https://api.mercadopago.com") String base,
            @ConfigProperty(name = "oficina.mercadopago.access-token") Optional<String> token,
            @ConfigProperty(name = "oficina.mercadopago.notification-url") Optional<String> notificacao,
            @ConfigProperty(name = "oficina.mercadopago.url-retorno") Optional<String> retorno,
            @ConfigProperty(name = "oficina.mercadopago.link-sandbox", defaultValue = "true") boolean sandbox,
            @ConfigProperty(name = "oficina.pagamento.simulador.url-base", defaultValue = "http://localhost:8082")
            String urlSimulador) {
        if ("mercadopago".equalsIgnoreCase(tipo)) {
            String accessToken = token.filter(t -> !t.isBlank()).orElseThrow(() -> new IllegalStateException(
                    "PAGAMENTO_PROVEDOR=mercadopago exige MP_ACCESS_TOKEN"));
            LOG.infof("Provedor de pagamento: Mercado Pago (%s, link %s)", base, sandbox ? "sandbox" : "produção");
            return new MercadoPagoGateway(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json,
                    URI.create(base), accessToken, notificacao.orElse(null), retorno.orElse(null), sandbox);
        }
        LOG.info("Provedor de pagamento: SIMULADO (sem credenciais do Mercado Pago)");
        return new ProvedorSimulado(urlSimulador, relogio);
    }

    @Produces
    @ApplicationScoped
    BillingController billingController(
            OrcamentoGateway orcamentos, ProvedorPagamentoGateway provedor, MetricasGateway metricas, Clock relogio,
            @ConfigProperty(name = "oficina.pagamento.validade", defaultValue = "PT30M") Duration validade,
            @ConfigProperty(name = "oficina.pagamento.tentativas-estorno", defaultValue = "3") int tentativasEstorno) {
        return new BillingController(
                new ComandosBillingUseCase(orcamentos, provedor, metricas, relogio, validade, tentativasEstorno),
                new AcompanharPagamentosUseCase(orcamentos, provedor, metricas, relogio),
                orcamentos);
    }

    @Produces
    @Singleton
    @DefaultBean
    Clock relogio() {
        return Clock.systemUTC();
    }
}
