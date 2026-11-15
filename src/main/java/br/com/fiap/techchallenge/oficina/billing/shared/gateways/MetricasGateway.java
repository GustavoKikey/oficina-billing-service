package br.com.fiap.techchallenge.oficina.billing.shared.gateways;

/** Métricas de negócio do Billing — adapter best-effort (fase 3). */
public interface MetricasGateway {

    /** {@code oficina_pagamentos_total{status}}: SOLICITADO, CONFIRMADO, RECUSADO, EXPIRADO, ESTORNADO. */
    void pagamento(String status);

    void falhaIntegracao(String integracao);

    void falhaProcessamento(String operacao);
}
