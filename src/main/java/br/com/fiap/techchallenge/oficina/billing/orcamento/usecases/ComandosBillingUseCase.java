package br.com.fiap.techchallenge.oficina.billing.orcamento.usecases;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.OrcamentoInvalidoException;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Pagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.StatusOrcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.OrcamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Os quatro comandos que o orquestrador manda ao Billing ({@code contratos-mensagens.md} §3),
 * idempotentes pela chave de negócio (um orçamento por OS): repetidos, respondem de novo
 * com base no estado atual, sem repetir o efeito (ADR 010).
 */
public class ComandosBillingUseCase {

    private static final System.Logger LOG = System.getLogger(ComandosBillingUseCase.class.getName());

    private final OrcamentoGateway orcamentos;
    private final ProvedorPagamentoGateway provedor;
    private final MetricasGateway metricas;
    private final Clock relogio;
    private final Duration validadePagamento;
    private final int tentativasEstorno;

    public ComandosBillingUseCase(OrcamentoGateway orcamentos, ProvedorPagamentoGateway provedor,
                                  MetricasGateway metricas, Clock relogio, Duration validadePagamento,
                                  int tentativasEstorno) {
        this.orcamentos = orcamentos;
        this.provedor = provedor;
        this.metricas = metricas;
        this.relogio = relogio;
        this.validadePagamento = validadePagamento;
        this.tentativasEstorno = Math.max(1, tentativasEstorno);
    }

    /** Item como veio na mensagem: validado só aqui, para virar {@code OrcamentoNaoGerado} e não DLQ. */
    public record ItemSolicitado(String tipo, String referenciaId, String descricao, int quantidade,
                                 String valorUnitario) {}

    public record GerarOrcamento(UUID messageId, OrdemServicoId osId, Orcamento.Cliente cliente,
                                 List<ItemSolicitado> itens) {}

    public record SolicitarPagamento(UUID messageId, OrdemServicoId osId, String aprovadoPor,
                                     OffsetDateTime aprovadoEm) {}

    public record CancelarOrcamento(UUID messageId, OrdemServicoId osId, String motivo) {}

    public record EstornarPagamento(UUID messageId, OrdemServicoId osId, String motivo) {}

    // ---------------------------------------------------------------------

    public EventoBilling gerarOrcamento(GerarOrcamento cmd) {
        Optional<Orcamento> existente = orcamentos.buscarPorOs(cmd.osId());
        if (existente.isPresent()) {
            return reenviar(EventoBilling.orcamentoGerado(existente.get()), cmd.osId(), cmd.messageId());
        }
        Orcamento orcamento;
        try {
            List<Orcamento.Item> itens = cmd.itens() == null ? List.of() : cmd.itens().stream()
                    .map(i -> new Orcamento.Item(i.tipo(), i.referenciaId(), i.descricao(), i.quantidade(),
                            valor(i.valorUnitario())))
                    .toList();
            orcamento = Orcamento.gerar(cmd.osId(), cmd.cliente(), itens, agora());
        } catch (OrcamentoInvalidoException e) {
            return reenviar(EventoBilling.orcamentoNaoGerado(cmd.osId().valor().toString(), e.getMessage()),
                    cmd.osId(), cmd.messageId());
        }
        return gravar(orcamento, EventoBilling.orcamentoGerado(orcamento), cmd.messageId());
    }

    /**
     * Cliente aprovou: cria a cobrança no Mercado Pago. A chamada externa vem antes da
     * gravação; se a gravação falhar, o reenvio cria outra preferência — a anterior só
     * expira, sem cobrar ninguém.
     */
    public EventoBilling solicitarPagamento(SolicitarPagamento cmd) {
        Orcamento orcamento = exigir(cmd.osId());
        if (orcamento.status() == StatusOrcamento.AGUARDANDO_PAGAMENTO || orcamento.status() == StatusOrcamento.PAGO) {
            return reenviar(EventoBilling.pagamentoSolicitado(orcamento), cmd.osId(), cmd.messageId());
        }
        if (orcamento.status() != StatusOrcamento.GERADO) {
            LOG.log(System.Logger.Level.INFO, "SolicitarPagamento para orçamento " + orcamento.status()
                    + " da OS " + cmd.osId() + " — a saga já seguiu adiante; nada a cobrar");
            return null;
        }
        OffsetDateTime agora = agora();
        var cobranca = provedor.criarCobranca(orcamento, agora.plus(validadePagamento));
        orcamento.solicitarPagamento(cmd.aprovadoPor(), cmd.aprovadoEm(), cobranca, agora);
        metricas.pagamento("SOLICITADO");
        return gravar(orcamento, EventoBilling.pagamentoSolicitado(orcamento), cmd.messageId());
    }

    /**
     * Compensação antes da cobrança. Se o pagamento foi aprovado no último instante (corrida
     * com o prazo), estorna: orçamento cancelado nunca fica com dinheiro (RFC 004 §3.4).
     */
    public EventoBilling cancelarOrcamento(CancelarOrcamento cmd) {
        Optional<Orcamento> encontrado = orcamentos.buscarPorOs(cmd.osId());
        if (encontrado.isEmpty()) {
            return reenviar(EventoBilling.orcamentoCancelado(cmd.osId().valor().toString(), null),
                    cmd.osId(), cmd.messageId());
        }
        Orcamento orcamento = encontrado.get();
        EventoBilling confirmacao = EventoBilling.orcamentoCancelado(cmd.osId().valor().toString(),
                orcamento.id().toString());
        if (orcamento.status() == StatusOrcamento.CANCELADO || orcamento.status() == StatusOrcamento.ESTORNADO) {
            return reenviar(confirmacao, cmd.osId(), cmd.messageId());
        }
        if (orcamento.precisaDeEstorno()) {
            String ref = provedor.estornar(orcamento.pagamento().idPagamentoProvedor(), "estorno-" + orcamento.id());
            orcamento.registrarEstorno(ref, cmd.motivo(), agora());
            metricas.pagamento("ESTORNADO");
            return gravar(orcamento, confirmacao, cmd.messageId());
        }
        orcamento.cancelar(cmd.motivo(), agora());
        return gravar(orcamento, confirmacao, cmd.messageId());
    }

    /**
     * Compensação depois da cobrança: estorno total no Mercado Pago. Falha técnica é
     * retentada aqui; esgotada, responde {@code EstornoFalhou} — dinheiro de cliente preso
     * precisa de uma pessoa (a saga vai para FALHA e alerta).
     */
    public EventoBilling estornarPagamento(EstornarPagamento cmd) {
        Optional<Orcamento> encontrado = orcamentos.buscarPorOs(cmd.osId());
        if (encontrado.isEmpty()) {
            return reenviar(new EventoBilling("PagamentoEstornado", java.util.Map.of(
                    "osId", cmd.osId().valor().toString(), "valorEstornado", "0.00")), cmd.osId(), cmd.messageId());
        }
        Orcamento orcamento = encontrado.get();
        if (orcamento.pagamento() != null
                && orcamento.pagamento().status() == Pagamento.Status.ESTORNADO) {
            return reenviar(EventoBilling.pagamentoEstornado(orcamento, valor(orcamento)), cmd.osId(), cmd.messageId());
        }
        if (!orcamento.precisaDeEstorno()) {
            // Nada foi cobrado: cancelar basta para não cobrar depois.
            orcamento.cancelar(cmd.motivo(), agora());
            return gravar(orcamento, EventoBilling.pagamentoEstornado(orcamento, "0.00"), cmd.messageId());
        }

        String idPagamento = orcamento.pagamento().idPagamentoProvedor();
        String ultimoErro = null;
        for (int tentativa = 1; tentativa <= tentativasEstorno; tentativa++) {
            try {
                String ref = provedor.estornar(idPagamento, "estorno-" + orcamento.id());
                orcamento.registrarEstorno(ref, cmd.motivo(), agora());
                metricas.pagamento("ESTORNADO");
                return gravar(orcamento, EventoBilling.pagamentoEstornado(orcamento, valor(orcamento)), cmd.messageId());
            } catch (ProvedorPagamentoGateway.FalhaProvedorException e) {
                ultimoErro = e.getMessage();
                metricas.falhaIntegracao("mercadopago-estorno");
                LOG.log(System.Logger.Level.WARNING,
                        "Estorno da OS " + cmd.osId() + " falhou (" + tentativa + "/" + tentativasEstorno + "): " + ultimoErro);
            }
        }
        orcamento.registrarFalhaDeEstorno(ultimoErro, agora());
        return gravar(orcamento, EventoBilling.estornoFalhou(orcamento, ultimoErro), cmd.messageId());
    }

    // ---------------------------------------------------------------------

    private Orcamento exigir(OrdemServicoId osId) {
        return orcamentos.buscarPorOs(osId).orElseThrow(() -> new IllegalStateException(
                "Nenhum orçamento para a OS " + osId + " — comando fora de ordem; vai para retentativa"));
    }

    private EventoBilling gravar(Orcamento orcamento, EventoBilling evento, UUID causa) {
        orcamentos.salvar(orcamento, List.of(evento), causa);
        return evento;
    }

    private EventoBilling reenviar(EventoBilling evento, OrdemServicoId osId, UUID causa) {
        orcamentos.publicar(List.of(evento), osId, causa);
        return evento;
    }

    private static Dinheiro valor(String texto) {
        try {
            return Dinheiro.de(texto);
        } catch (RuntimeException e) {
            throw new OrcamentoInvalidoException("Valor unitário inválido: " + texto);
        }
    }

    private static String valor(Orcamento o) {
        return o.pagamento().valor().valor().toPlainString();
    }

    private OffsetDateTime agora() {
        return OffsetDateTime.now(relogio);
    }
}
