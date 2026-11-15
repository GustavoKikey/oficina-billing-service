package br.com.fiap.techchallenge.oficina.billing.orcamento.gateways;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência do orçamento <b>com outbox atômico</b> (ADR 009/010): estado e eventos são
 * gravados juntos ({@code TransactWriteItems} no DynamoDB) ou nada é gravado.
 */
public interface OrcamentoGateway {

    /** Leitura consistente pela chave — usada pelos comandos da saga. */
    Optional<Orcamento> buscarPorOs(OrdemServicoId osId);

    /** Pelo id do orçamento ({@code external_reference} no Mercado Pago) — webhook e consulta periódica. */
    Optional<Orcamento> buscarPorId(UUID orcamentoId);

    /** Orçamentos com cobrança pendente, os que vencem primeiro antes. */
    List<Orcamento> comPagamentoPendente(int limite);

    /**
     * Grava o orçamento e enfileira os eventos na mesma transação. Concorrência otimista:
     * se outra réplica gravou antes, lança {@link ConflitoDeVersaoException}.
     *
     * @param causationId comando que causou a mudança; nulo para webhook e consulta periódica
     */
    void salvar(Orcamento orcamento, List<EventoBilling> eventos, UUID causationId);

    /** Só enfileira eventos — resposta a comando que não muda estado (ex.: duplicata). */
    void publicar(List<EventoBilling> eventos, OrdemServicoId correlationId, UUID causationId);

    class ConflitoDeVersaoException extends RuntimeException {
        public ConflitoDeVersaoException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
