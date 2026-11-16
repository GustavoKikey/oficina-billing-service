package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento.Desfecho;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento.Situacao;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regras do agregado Orçamento, sem banco nem provedor. */
class OrcamentoTest {

    private static final OffsetDateTime AGORA = OffsetDateTime.of(2026, 11, 15, 14, 0, 0, 0, ZoneOffset.UTC);
    private static final Orcamento.Cliente ANA = new Orcamento.Cliente("c-1", "Ana Souza", "ana@exemplo.com");

    private static Orcamento.Item item(String descricao, int quantidade, String valor) {
        return new Orcamento.Item("PECA", "ref-" + descricao, descricao, quantidade, Dinheiro.de(valor));
    }

    private static Orcamento gerado() {
        return Orcamento.gerar(OrdemServicoId.novo(), ANA,
                List.of(item("Alinhamento", 1, "120.00"), item("Pastilha de freio", 2, "95.00")), AGORA);
    }

    private static Orcamento aguardandoPagamento() {
        Orcamento o = gerado();
        o.solicitarPagamento("CLIENTE", AGORA, new CobrancaCriada("pref-1", "https://mp/checkout", AGORA.plusMinutes(30)),
                AGORA);
        return o;
    }

    private static SituacaoPagamento situacao(Situacao s) {
        return new SituacaoPagamento("pag-1", "orc", s, "detalhe " + s, Dinheiro.de("310.00"), AGORA.plusMinutes(5));
    }

    @Nested
    @DisplayName("geração")
    class Geracao {

        @Test
        void valorTotalVemDosItens() {
            Orcamento o = gerado();
            assertEquals(Dinheiro.de("310.00"), o.valorTotal());
            assertEquals(StatusOrcamento.GERADO, o.status());
            assertNull(o.pagamento());
        }

        @Test
        void diagnosticoSemItensNaoGeraOrcamento() {
            assertThrows(OrcamentoInvalidoException.class,
                    () -> Orcamento.gerar(OrdemServicoId.novo(), ANA, List.of(), AGORA));
            assertThrows(OrcamentoInvalidoException.class,
                    () -> Orcamento.gerar(OrdemServicoId.novo(), ANA, null, AGORA));
        }

        @Test
        void itemPrecisaDeDescricaoQuantidadeEValor() {
            assertThrows(OrcamentoInvalidoException.class, () -> item(null, 1, "10.00"));
            assertThrows(OrcamentoInvalidoException.class, () -> item("  ", 1, "10.00"));
            assertThrows(OrcamentoInvalidoException.class, () -> item("Filtro", 0, "10.00"));
            assertThrows(OrcamentoInvalidoException.class,
                    () -> new Orcamento.Item("PECA", "ref", "Filtro", 1, null));
            assertEquals(Dinheiro.de("0.00"), item("Cortesia", 1, "0.00").subtotal(), "item gratuito é válido");
        }

        @Test
        void soSolicitaPagamentoUmaVez() {
            Orcamento o = aguardandoPagamento();
            assertThrows(IllegalStateException.class, () -> o.solicitarPagamento("ATENDENTE", AGORA,
                    new CobrancaCriada("pref-2", "https://mp/outro", AGORA.plusMinutes(30)), AGORA));
            assertEquals("CLIENTE", o.aprovadoPor());
        }
    }

    @Nested
    @DisplayName("situação informada pelo provedor")
    class SituacaoDoProvedor {

        @Test
        void semCobrancaNadaMuda() {
            assertEquals(Desfecho.NADA_MUDOU, gerado().registrarSituacao(situacao(Situacao.APROVADO)));
        }

        @Test
        void aprovadoConfirmaUmaVezSo() {
            Orcamento o = aguardandoPagamento();
            assertEquals(Desfecho.CONFIRMADO, o.registrarSituacao(situacao(Situacao.APROVADO)));
            assertEquals(StatusOrcamento.PAGO, o.status());
            assertEquals("pag-1", o.pagamento().idPagamentoProvedor());
            assertEquals(Desfecho.NADA_MUDOU, o.registrarSituacao(situacao(Situacao.APROVADO)), "webhook + consulta");
        }

        @Test
        void recusadoEPendente() {
            Orcamento pendente = aguardandoPagamento();
            assertEquals(Desfecho.NADA_MUDOU, pendente.registrarSituacao(situacao(Situacao.PENDENTE)));
            assertTrue(pendente.pagamento().pendente());

            Orcamento recusado = aguardandoPagamento();
            assertEquals(Desfecho.RECUSADO, recusado.registrarSituacao(situacao(Situacao.RECUSADO)));
            assertEquals(Pagamento.Status.RECUSADO, recusado.pagamento().status());
            assertEquals(Desfecho.NADA_MUDOU, recusado.registrarSituacao(situacao(Situacao.APROVADO)),
                    "pagamento já decidido não muda");
        }

        @Test
        void pagoDepoisDoCancelamentoPedeEstornoUmaVez() {
            Orcamento o = aguardandoPagamento();
            o.cancelar("PAGAMENTO_EXPIRADO", AGORA.plusMinutes(31));

            assertEquals(Desfecho.ESTORNO_NECESSARIO, o.registrarSituacao(situacao(Situacao.APROVADO)));
            assertTrue(o.precisaDeEstorno());
            assertEquals(Desfecho.NADA_MUDOU, o.registrarSituacao(situacao(Situacao.APROVADO)), "já marcado");

            o.registrarEstorno("est-1", "PAGO_APOS_CANCELAMENTO", AGORA.plusMinutes(32));
            assertEquals(StatusOrcamento.CANCELADO, o.status(), "continua cancelado; o dinheiro voltou");
            assertEquals("PAGAMENTO_EXPIRADO", o.motivoCancelamento(), "motivo original preservado");
            assertEquals(Desfecho.NADA_MUDOU, o.registrarSituacao(situacao(Situacao.APROVADO)), "já estornado");
        }

        @Test
        void recusaDeOrcamentoCanceladoNaoMudaNada() {
            Orcamento o = aguardandoPagamento();
            o.cancelar("ORCAMENTO_RECUSADO", AGORA);
            assertEquals(Desfecho.NADA_MUDOU, o.registrarSituacao(situacao(Situacao.RECUSADO)));
        }
    }

    @Nested
    @DisplayName("expiração do link")
    class Expiracao {

        @Test
        void soExpiraDepoisDoPrazoEUmaVez() {
            Orcamento o = aguardandoPagamento();
            assertFalse(o.expirarSeVencido(AGORA.plusMinutes(29)));
            assertTrue(o.expirarSeVencido(AGORA.plusMinutes(30)), "no instante do vencimento");
            assertEquals(Pagamento.Status.EXPIRADO, o.pagamento().status());
            assertFalse(o.expirarSeVencido(AGORA.plusMinutes(40)));
        }

        @Test
        void semCobrancaOuJaPagoNaoExpira() {
            assertFalse(gerado().expirarSeVencido(AGORA.plusDays(1)));
            Orcamento pago = aguardandoPagamento();
            pago.registrarSituacao(situacao(Situacao.APROVADO));
            assertFalse(pago.expirarSeVencido(AGORA.plusDays(1)));
        }
    }

    @Nested
    @DisplayName("compensações")
    class Compensacoes {

        @Test
        void cancelarEhIdempotenteECancelaACobrancaPendente() {
            Orcamento o = aguardandoPagamento();
            o.cancelar("PAGAMENTO_RECUSADO", AGORA);
            o.cancelar("OUTRO", AGORA.plusMinutes(1));
            assertEquals(StatusOrcamento.CANCELADO, o.status());
            assertEquals("PAGAMENTO_RECUSADO", o.motivoCancelamento());
            assertEquals(Pagamento.Status.CANCELADO, o.pagamento().status());
        }

        @Test
        void cancelarSemCobrancaOuComCobrancaJaDecidida() {
            Orcamento semCobranca = gerado();
            semCobranca.cancelar("ORCAMENTO_RECUSADO", AGORA);
            assertEquals(StatusOrcamento.CANCELADO, semCobranca.status());

            Orcamento expirado = aguardandoPagamento();
            expirado.expirarSeVencido(AGORA.plusHours(1));
            expirado.cancelar("PAGAMENTO_EXPIRADO", AGORA.plusHours(1));
            assertEquals(Pagamento.Status.EXPIRADO, expirado.pagamento().status(), "expirado não vira cancelado");
        }

        @Test
        void pagoNaoSeCancelaEstorna() {
            Orcamento o = aguardandoPagamento();
            o.registrarSituacao(situacao(Situacao.APROVADO));
            assertThrows(IllegalStateException.class, () -> o.cancelar("REPARO_NAO_LIBERADO", AGORA));

            o.registrarEstorno("est-1", "REPARO_NAO_LIBERADO", AGORA.plusMinutes(10));
            assertEquals(StatusOrcamento.ESTORNADO, o.status());
            assertEquals("REPARO_NAO_LIBERADO", o.motivoCancelamento());
            assertEquals(Pagamento.Status.ESTORNADO, o.pagamento().status());
            assertFalse(o.precisaDeEstorno());

            o.cancelar("REPETIDO", AGORA.plusMinutes(11));
            assertEquals(StatusOrcamento.ESTORNADO, o.status(), "estornado já está compensado");
        }

        @Test
        void estornoSemPagamentoAprovadoEhErro() {
            assertThrows(IllegalStateException.class, () -> gerado().registrarEstorno("est", "m", AGORA));
            assertThrows(IllegalStateException.class, () -> aguardandoPagamento().registrarEstorno("est", "m", AGORA));
        }

        @Test
        void falhaDeEstornoFicaNoHistorico() {
            Orcamento o = aguardandoPagamento();
            o.registrarSituacao(situacao(Situacao.APROVADO));
            o.registrarFalhaDeEstorno("timeout", AGORA);
            assertTrue(o.pagamento().historico().stream().anyMatch(h -> h.contains("estorno falhou: timeout")));

            gerado().registrarFalhaDeEstorno("sem cobrança", AGORA); // não explode sem pagamento
        }
    }
}
