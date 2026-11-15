package br.com.fiap.techchallenge.oficina.billing.external.observabilidade;

import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * {@code oficina_pagamentos_total{status}}, {@code oficina_integracao_falhas_total{integracao}}
 * e {@code oficina_billing_falhas_total{operacao}} em {@code /q/metrics}.
 */
@ApplicationScoped
public class MicrometerMetricasGateway implements MetricasGateway {

    private static final Logger LOG = Logger.getLogger(MicrometerMetricasGateway.class);

    private final MeterRegistry registry;

    public MicrometerMetricasGateway(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void pagamento(String status) {
        contar("oficina.pagamentos", "status", status);
    }

    @Override
    public void falhaIntegracao(String integracao) {
        contar("oficina.integracao.falhas", "integracao", integracao);
    }

    @Override
    public void falhaProcessamento(String operacao) {
        contar("oficina.billing.falhas", "operacao", operacao);
    }

    private void contar(String nome, String tag, String valor) {
        try {
            registry.counter(nome, tag, valor == null ? "desconhecido" : valor).increment();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Falha ao registrar a métrica %s — operação de negócio não afetada.", nome);
        }
    }
}
