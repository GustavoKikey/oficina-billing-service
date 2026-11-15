package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * O que o provedor diz de um pagamento, consultado na API dele — nunca o conteúdo de um
 * webhook, que é só um aviso (RFC 004 §3.2).
 *
 * @param referenciaExterna o {@code external_reference}: o id do orçamento
 * @param detalhe           motivo do provedor (no Mercado Pago, {@code status_detail})
 */
public record SituacaoPagamento(String idPagamento, String referenciaExterna, Situacao situacao, String detalhe,
                                Dinheiro valor, OffsetDateTime quando) {

    public enum Situacao { APROVADO, RECUSADO, PENDENTE }

    public SituacaoPagamento {
        Objects.requireNonNull(idPagamento, "idPagamento");
        Objects.requireNonNull(situacao, "situacao");
        Objects.requireNonNull(quando, "quando");
    }
}
