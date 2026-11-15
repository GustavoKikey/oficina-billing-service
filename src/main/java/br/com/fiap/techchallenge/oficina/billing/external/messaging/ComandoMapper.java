package br.com.fiap.techchallenge.oficina.billing.external.messaging;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.usecases.ComandosBillingUseCase;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Traduz os comandos do orquestrador ({@code contratos-mensagens.md} §3). Só a forma é
 * conferida aqui (campos presentes, tipos certos — senão DLQ); a regra de negócio dos
 * itens é do use case, que responde {@code OrcamentoNaoGerado}.
 */
public final class ComandoMapper {

    private ComandoMapper() {}

    public static ComandosBillingUseCase.GerarOrcamento gerar(Envelope e) {
        JsonNode p = e.payload();
        JsonNode c = p == null ? null : p.get("cliente");
        Orcamento.Cliente cliente = c == null || c.isNull() ? null
                : new Orcamento.Cliente(texto(c, "id"), texto(c, "nome"), texto(c, "email"));
        JsonNode lista = p == null ? null : p.get("itens");
        if (lista == null || !lista.isArray()) {
            throw new EnvelopeInvalidoException("'itens' ausente ou não é lista");
        }
        List<ComandosBillingUseCase.ItemSolicitado> itens = new ArrayList<>();
        for (JsonNode i : lista) {
            itens.add(new ComandosBillingUseCase.ItemSolicitado(texto(i, "tipo"), texto(i, "referenciaId"),
                    texto(i, "descricao"), i.path("quantidade").asInt(0), texto(i, "valorUnitario")));
        }
        return new ComandosBillingUseCase.GerarOrcamento(e.messageId(), osId(e), cliente, itens);
    }

    public static ComandosBillingUseCase.SolicitarPagamento solicitar(Envelope e) {
        String aprovadoEm = texto(e.payload(), "aprovadoEm");
        try {
            return new ComandosBillingUseCase.SolicitarPagamento(e.messageId(), osId(e),
                    texto(e.payload(), "aprovadoPor"), aprovadoEm == null ? null : OffsetDateTime.parse(aprovadoEm));
        } catch (RuntimeException ex) {
            throw new EnvelopeInvalidoException("aprovadoEm não é um instante ISO-8601: " + aprovadoEm);
        }
    }

    public static ComandosBillingUseCase.CancelarOrcamento cancelar(Envelope e) {
        return new ComandosBillingUseCase.CancelarOrcamento(e.messageId(), osId(e), texto(e.payload(), "motivo"));
    }

    public static ComandosBillingUseCase.EstornarPagamento estornar(Envelope e) {
        return new ComandosBillingUseCase.EstornarPagamento(e.messageId(), osId(e), texto(e.payload(), "motivo"));
    }

    private static OrdemServicoId osId(Envelope e) {
        return OrdemServicoId.de(e.correlationId());
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no == null ? null : no.get(campo);
        return valor == null || valor.isNull() ? null : valor.asText();
    }
}
