package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

import br.com.fiap.techchallenge.oficina.billing.shared.entities.DomainException;

/** Itens que não formam um orçamento (lista vazia, valor negativo). Vira {@code OrcamentoNaoGerado}. */
public class OrcamentoInvalidoException extends DomainException {

    public OrcamentoInvalidoException(String mensagem) {
        super(mensagem);
    }
}
