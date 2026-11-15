package br.com.fiap.techchallenge.oficina.billing.orcamento.usecases;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Pagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.StatusOrcamento;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import br.com.fiap.techchallenge.oficina.billing.suporte.Fakes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static br.com.fiap.techchallenge.oficina.billing.suporte.Fakes.AGORA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comandos da saga e acompanhamento de pagamentos contra fakes. */
class BillingUseCasesTest {

    private static final Duration VALIDADE = Duration.ofMinutes(30);

    private final Fakes.Orcamentos orcamentos = new Fakes.Orcamentos();
    private final Fakes.Provedor provedor = new Fakes.Provedor();
    private final Fakes.Metricas metricas = new Fakes.Metricas();
    private final Fakes.Relogio relogio = new Fakes.Relogio();

    private ComandosBillingUseCase comandos;
    private AcompanharPagamentosUseCase acompanhar;
    private OrdemServicoId os;

    @BeforeEach
    void preparar() {
        comandos = new ComandosBillingUseCase(orcamentos, provedor, metricas, relogio, VALIDADE, 3);
        acompanhar = new AcompanharPagamentosUseCase(orcamentos, provedor, metricas, relogio);
        os = OrdemServicoId.novo();
    }

    private static List<ComandosBillingUseCase.ItemSolicitado> itens() {
        return List.of(
                new ComandosBillingUseCase.ItemSolicitado("SERVICO", "s1", "Alinhamento", 1, "120.00"),
                new ComandosBillingUseCase.ItemSolicitado("PECA", "p1", "Pastilha de freio", 2, "95.00"));
    }

    private EventoBilling gerar() {
        return comandos.gerarOrcamento(new ComandosBillingUseCase.GerarOrcamento(UUID.randomUUID(), os,
                new Orcamento.Cliente("c1", "Alice", "alice@exemplo.com"), itens()));
    }

    private EventoBilling solicitar() {
        return comandos.solicitarPagamento(new ComandosBillingUseCase.SolicitarPagamento(UUID.randomUUID(), os,
                "CLIENTE", AGORA));
    }

    private Orcamento orcamento() {
        return orcamentos.dados.get(os);
    }

    private Orcamento ateConfirmado() {
        gerar();
        solicitar();
        provedor.pagar(orcamento(), SituacaoPagamento.Situacao.APROVADO, AGORA.plusMinutes(5));
        acompanhar.verificarPendentes();
        return orcamento();
    }

    // ---------- GerarOrcamento ----------

    @Test
    void gerarCalculaOValorAPartirDosItens() {
        EventoBilling e = gerar();

        assertEquals("OrcamentoGerado", e.tipo());
        assertEquals("310.00", e.payload().get("valorTotal"));
        assertEquals("billing.orcamento-gerado", e.routingKey());
        assertEquals(StatusOrcamento.GERADO, orcamento().status());
    }

    @Test
    void gerarDeNovoReenviaOMesmoOrcamento() {
        gerar();
        String id = orcamento().id().toString();

        EventoBilling repetido = gerar();

        assertEquals(id, repetido.payload().get("orcamentoId"));
        assertEquals(1, orcamentos.dados.size());
        assertEquals(2, orcamentos.outbox.size());
    }

    @Test
    @DisplayName("itens inválidos viram OrcamentoNaoGerado (a saga compensa), nunca DLQ")
    void itensInvalidosNaoGeramOrcamento() {
        for (List<ComandosBillingUseCase.ItemSolicitado> ruins : List.of(
                List.<ComandosBillingUseCase.ItemSolicitado>of(),
                List.of(new ComandosBillingUseCase.ItemSolicitado("PECA", "p", "Peça", 1, "-5.00")),
                List.of(new ComandosBillingUseCase.ItemSolicitado("PECA", "p", "Peça", 0, "5.00")),
                List.of(new ComandosBillingUseCase.ItemSolicitado("PECA", "p", "Peça", 1, "abc")))) {
            EventoBilling e = comandos.gerarOrcamento(new ComandosBillingUseCase.GerarOrcamento(UUID.randomUUID(),
                    OrdemServicoId.novo(), null, ruins));
            assertEquals("OrcamentoNaoGerado", e.tipo());
        }
        assertTrue(orcamentos.dados.isEmpty());
    }

    // ---------- SolicitarPagamento ----------

    @Test
    void solicitarCriaACobrancaComValidade() {
        gerar();

        EventoBilling e = solicitar();

        assertEquals("PagamentoSolicitado", e.tipo());
        assertEquals("https://mp.test/checkout/" + orcamento().id(), e.payload().get("linkPagamento"));
        assertEquals(AGORA.plus(VALIDADE).toString(), e.payload().get("expiraEm"));
        assertEquals(StatusOrcamento.AGUARDANDO_PAGAMENTO, orcamento().status());
        assertEquals("CLIENTE", orcamento().aprovadoPor());
        assertEquals("PagamentoSolicitado", solicitar().tipo(), "repetido: mesmo link, sem nova cobrança");
        assertTrue(metricas.registros.contains("pagamento:SOLICITADO"));
    }

    @Test
    void solicitarSemOrcamentoEForaDeOrdem() {
        assertThrows(IllegalStateException.class, this::solicitar);
    }

    @Test
    void solicitarDepoisDeCanceladoNaoCobra() {
        gerar();
        comandos.cancelarOrcamento(new ComandosBillingUseCase.CancelarOrcamento(UUID.randomUUID(), os, "X"));

        assertNull(solicitar());
        assertNull(orcamento().pagamento());
    }

    // ---------- acompanhamento ----------

    @Test
    @DisplayName("consulta periódica: aprovado na API vira PagamentoConfirmado")
    void aprovadoViraConfirmado() {
        Orcamento o = ateConfirmado();

        assertEquals(StatusOrcamento.PAGO, o.status());
        assertEquals("PagamentoConfirmado", orcamentos.ultimo().tipo());
        assertEquals("310.00", orcamentos.ultimo().payload().get("valorPago"));
        assertEquals(0, acompanhar.verificarPendentes(), "pago sai da lista de pendentes");
    }

    @Test
    void recusadoViraPagamentoRecusado() {
        gerar();
        solicitar();
        provedor.pagar(orcamento(), SituacaoPagamento.Situacao.RECUSADO, AGORA.plusMinutes(5));

        assertEquals(1, acompanhar.verificarPendentes());

        assertEquals("PagamentoRecusado", orcamentos.ultimo().tipo());
        assertEquals("cc_rejected_other_reason", orcamentos.ultimo().payload().get("motivo"));
    }

    @Test
    void pendenteNaoMudaNadaAteVencer() {
        gerar();
        solicitar();
        provedor.pagar(orcamento(), SituacaoPagamento.Situacao.PENDENTE, AGORA);
        assertEquals(0, acompanhar.verificarPendentes());

        relogio.avancar(VALIDADE.plusSeconds(1));
        assertEquals(1, acompanhar.verificarPendentes());

        assertEquals("PagamentoExpirado", orcamentos.ultimo().tipo());
        assertEquals(Pagamento.Status.EXPIRADO, orcamento().pagamento().status());
    }

    @Test
    void falhaDeConsultaNaoDerrubaOsDemais() {
        gerar();
        solicitar();
        provedor.consultaFalha = true;

        assertEquals(0, acompanhar.verificarPendentes());
        assertTrue(metricas.registros.contains("falha:mercadopago-consulta"));
    }

    @Test
    @DisplayName("webhook: consulta o pagamento informado e aplica")
    void webhookConsultaEAplica() {
        gerar();
        solicitar();
        var pago = provedor.pagar(orcamento(), SituacaoPagamento.Situacao.APROVADO, AGORA);

        assertTrue(acompanhar.processarNotificacao(pago.idPagamento()));
        assertEquals(StatusOrcamento.PAGO, orcamento().status());
        assertFalse(acompanhar.processarNotificacao(pago.idPagamento()), "repetido: nada muda");
        assertFalse(acompanhar.processarNotificacao("desconhecido"));
    }

    @Test
    @DisplayName("pago depois de cancelado: estorno automático — orçamento cancelado nunca fica com dinheiro")
    void pagoDepoisDeCanceladoEEstornado() {
        gerar();
        solicitar();
        comandos.cancelarOrcamento(new ComandosBillingUseCase.CancelarOrcamento(UUID.randomUUID(), os,
                "PAGAMENTO_EXPIRADO"));
        var pago = provedor.pagar(orcamento(), SituacaoPagamento.Situacao.APROVADO, AGORA.plusMinutes(31));

        assertTrue(acompanhar.processarNotificacao(pago.idPagamento()));

        assertEquals(List.of(pago.idPagamento()), provedor.estornos);
        assertEquals(StatusOrcamento.CANCELADO, orcamento().status());
        assertEquals(Pagamento.Status.ESTORNADO, orcamento().pagamento().status());
    }

    // ---------- compensações ----------

    @Test
    void cancelarAntesDoPagamento() {
        gerar();
        solicitar();

        EventoBilling e = comandos.cancelarOrcamento(new ComandosBillingUseCase.CancelarOrcamento(UUID.randomUUID(),
                os, "ORCAMENTO_RECUSADO"));

        assertEquals("OrcamentoCancelado", e.tipo());
        assertEquals(StatusOrcamento.CANCELADO, orcamento().status());
        assertEquals(Pagamento.Status.CANCELADO, orcamento().pagamento().status());
        assertEquals("OrcamentoCancelado", comandos.cancelarOrcamento(new ComandosBillingUseCase.CancelarOrcamento(
                UUID.randomUUID(), os, "X")).tipo(), "repetido confirma de novo");
        assertEquals("OrcamentoCancelado", comandos.cancelarOrcamento(new ComandosBillingUseCase.CancelarOrcamento(
                UUID.randomUUID(), OrdemServicoId.novo(), "X")).tipo(), "sem orçamento também confirma");
    }

    @Test
    void cancelarOrcamentoJaPagoEstorna() {
        ateConfirmado();

        comandos.cancelarOrcamento(new ComandosBillingUseCase.CancelarOrcamento(UUID.randomUUID(), os, "X"));

        assertEquals(1, provedor.estornos.size());
        assertEquals(StatusOrcamento.ESTORNADO, orcamento().status());
    }

    @Test
    @DisplayName("estorno: devolve no provedor, com retentativa, e confirma")
    void estornarComRetentativa() {
        ateConfirmado();
        provedor.falhasDeEstornoRestantes = 2;

        EventoBilling e = comandos.estornarPagamento(new ComandosBillingUseCase.EstornarPagamento(UUID.randomUUID(),
                os, "REPARO_NAO_LIBERADO"));

        assertEquals("PagamentoEstornado", e.tipo());
        assertEquals("310.00", e.payload().get("valorEstornado"));
        assertEquals(StatusOrcamento.ESTORNADO, orcamento().status());
        assertEquals("REPARO_NAO_LIBERADO", orcamento().motivoCancelamento());
        assertEquals(1, provedor.estornos.size());
        assertEquals("PagamentoEstornado", comandos.estornarPagamento(new ComandosBillingUseCase.EstornarPagamento(
                UUID.randomUUID(), os, "X")).tipo(), "repetido: não estorna de novo");
        assertEquals(1, provedor.estornos.size());
    }

    @Test
    @DisplayName("estorno que esgota as tentativas responde EstornoFalhou (a saga vai para FALHA)")
    void estornoQueFalhaDeVez() {
        ateConfirmado();
        provedor.falhasDeEstornoRestantes = 5;

        EventoBilling e = comandos.estornarPagamento(new ComandosBillingUseCase.EstornarPagamento(UUID.randomUUID(),
                os, "REPARO_NAO_LIBERADO"));

        assertEquals("EstornoFalhou", e.tipo());
        assertEquals(StatusOrcamento.PAGO, orcamento().status());
        assertTrue(orcamento().pagamento().historico().stream().anyMatch(h -> h.contains("estorno falhou")));
    }

    @Test
    void estornarSemPagamentoAprovadoNaoDevolveNada() {
        gerar();
        solicitar();

        EventoBilling e = comandos.estornarPagamento(new ComandosBillingUseCase.EstornarPagamento(UUID.randomUUID(),
                os, "X"));
        assertEquals("0.00", e.payload().get("valorEstornado"));
        assertTrue(provedor.estornos.isEmpty());

        EventoBilling semOrcamento = comandos.estornarPagamento(new ComandosBillingUseCase.EstornarPagamento(
                UUID.randomUUID(), OrdemServicoId.novo(), "X"));
        assertEquals("0.00", semOrcamento.payload().get("valorEstornado"));
    }
}
