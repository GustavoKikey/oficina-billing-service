package br.com.fiap.techchallenge.oficina.billing.external.messaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Forma dos comandos: o que falta ou vem torto vai para a DLQ; regra de negócio, não. */
class ComandoMapperTest {

    private static Envelope comando(String tipo, JsonObject payload) {
        JsonObject json = EnvelopeTest.envelopeValido().put("type", tipo);
        if (payload == null) {
            json.remove("payload");
        } else {
            json.put("payload", payload);
        }
        return Envelope.de(json);
    }

    @Test
    void gerarOrcamentoComClienteEItens() {
        var cmd = ComandoMapper.gerar(comando("GerarOrcamento", new JsonObject()
                .put("cliente", new JsonObject().put("id", "c-1").put("nome", "Ana").putNull("email"))
                .put("itens", new JsonArray().add(new JsonObject()
                        .put("tipo", "PECA").put("referenciaId", "p-1").put("descricao", "Filtro")
                        .put("quantidade", 2).put("valorUnitario", "45.90")))));

        assertEquals("Ana", cmd.cliente().nome());
        assertNull(cmd.cliente().email(), "campo nulo vira null, não \"null\"");
        assertEquals(1, cmd.itens().size());
        assertEquals("45.90", cmd.itens().get(0).valorUnitario());
    }

    @Test
    void gerarOrcamentoSemClienteAindaGera() {
        var cmd = ComandoMapper.gerar(comando("GerarOrcamento", new JsonObject()
                .putNull("cliente")
                .put("itens", new JsonArray().add(new JsonObject().put("descricao", "Sem quantidade")))));

        assertNull(cmd.cliente());
        assertEquals(0, cmd.itens().get(0).quantidade(), "quantidade ausente fica para o use case recusar");
    }

    @Test
    void gerarOrcamentoSemListaDeItensEhMensagemInvalida() {
        assertThrows(EnvelopeInvalidoException.class,
                () -> ComandoMapper.gerar(comando("GerarOrcamento", new JsonObject())));
        assertThrows(EnvelopeInvalidoException.class,
                () -> ComandoMapper.gerar(comando("GerarOrcamento", new JsonObject().put("itens", "não é lista"))));
        assertThrows(EnvelopeInvalidoException.class, () -> ComandoMapper.gerar(comando("GerarOrcamento", null)));
    }

    @Test
    void solicitarPagamentoComOuSemInstanteDeAprovacao() {
        var comInstante = ComandoMapper.solicitar(comando("SolicitarPagamento", new JsonObject()
                .put("aprovadoPor", "CLIENTE").put("aprovadoEm", "2026-11-15T14:00:00Z")));
        assertEquals(OffsetDateTime.parse("2026-11-15T14:00:00Z"), comInstante.aprovadoEm());

        var semInstante = ComandoMapper.solicitar(comando("SolicitarPagamento", new JsonObject().put("aprovadoPor", "ATENDENTE")));
        assertNull(semInstante.aprovadoEm());

        assertThrows(EnvelopeInvalidoException.class, () -> ComandoMapper.solicitar(
                comando("SolicitarPagamento", new JsonObject().put("aprovadoEm", "ontem"))));
    }

    @Test
    void compensacoesLevamOMotivo() {
        assertEquals("ORCAMENTO_RECUSADO", ComandoMapper.cancelar(
                comando("CancelarOrcamento", new JsonObject().put("motivo", "ORCAMENTO_RECUSADO"))).motivo());
        assertEquals("REPARO_NAO_LIBERADO", ComandoMapper.estornar(
                comando("EstornarPagamento", new JsonObject().put("motivo", "REPARO_NAO_LIBERADO"))).motivo());
        assertNull(ComandoMapper.cancelar(comando("CancelarOrcamento", new JsonObject())).motivo());
    }
}
