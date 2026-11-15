package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

/**
 * <pre>
 *   GERADO                → AGUARDANDO_PAGAMENTO  (SolicitarPagamento: cliente aprovou)
 *   AGUARDANDO_PAGAMENTO  → PAGO                  (Mercado Pago confirmou)
 *   GERADO | AGUARDANDO_PAGAMENTO → CANCELADO     (recusa, prazo, pagamento recusado/expirado)
 *   PAGO                  → ESTORNADO             (EstornarPagamento: reparo não liberado)
 * </pre>
 */
public enum StatusOrcamento {
    GERADO, AGUARDANDO_PAGAMENTO, PAGO, CANCELADO, ESTORNADO
}
