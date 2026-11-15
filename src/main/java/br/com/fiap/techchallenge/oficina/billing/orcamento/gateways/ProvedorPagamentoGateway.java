package br.com.fiap.techchallenge.oficina.billing.orcamento.gateways;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.CobrancaCriada;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Provedor de pagamento (RFC 004). Dois adapters: Mercado Pago (Checkout Pro) e um
 * simulador para ambiente local, testes e ensaio da demonstração.
 *
 * <p>Falhas técnicas (rede, 5xx) lançam {@link FalhaProvedorException}; o chamador decide
 * se tenta de novo.
 */
public interface ProvedorPagamentoGateway {

    /** Cria a cobrança do valor do orçamento, com {@code external_reference} = id do orçamento. */
    CobrancaCriada criarCobranca(Orcamento orcamento, OffsetDateTime expiraEm);

    /** Pagamento mais relevante do orçamento (aprovado, se houver) — consulta periódica. */
    Optional<SituacaoPagamento> consultarPorOrcamento(Orcamento orcamento);

    /** Pagamento pelo id do provedor — o que o webhook informa. */
    Optional<SituacaoPagamento> consultarPagamento(String idPagamento);

    /**
     * Estorno total.
     *
     * @param chaveIdempotencia mesma chave, mesmo estorno: reenvio do comando não estorna duas vezes
     * @return referência do estorno no provedor
     */
    String estornar(String idPagamento, String chaveIdempotencia);

    class FalhaProvedorException extends RuntimeException {
        public FalhaProvedorException(String mensagem) {
            super(mensagem);
        }

        public FalhaProvedorException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
