package br.com.fiap.techchallenge.oficina.billing.orcamento.usecases;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.OrcamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Descobre o que aconteceu com as cobranças (RFC 004 §3.2). Dois caminhos, mesma ação —
 * <b>consultar o pagamento na API do provedor e agir sobre o que ela disser</b>:
 * <ul>
 *   <li>consulta periódica dos pendentes: a garantia, funciona sem URL pública;</li>
 *   <li>webhook: o atalho. O corpo do webhook nunca é confiável — é só um aviso.</li>
 * </ul>
 */
public class AcompanharPagamentosUseCase {

    private static final System.Logger LOG = System.getLogger(AcompanharPagamentosUseCase.class.getName());
    private static final int LOTE = 50;

    private final OrcamentoGateway orcamentos;
    private final ProvedorPagamentoGateway provedor;
    private final MetricasGateway metricas;
    private final Clock relogio;

    public AcompanharPagamentosUseCase(OrcamentoGateway orcamentos, ProvedorPagamentoGateway provedor,
                                       MetricasGateway metricas, Clock relogio) {
        this.orcamentos = orcamentos;
        this.provedor = provedor;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    /** @return quantos orçamentos mudaram nesta passada */
    public int verificarPendentes() {
        int mudaram = 0;
        for (Orcamento orcamento : orcamentos.comPagamentoPendente(LOTE)) {
            try {
                Optional<SituacaoPagamento> situacao = provedor.consultarPorOrcamento(orcamento);
                if (aplicar(orcamento, situacao)) {
                    mudaram++;
                }
            } catch (RuntimeException e) {
                // Um orçamento com problema (provedor fora, conflito de versão) não impede os demais.
                metricas.falhaIntegracao("mercadopago-consulta");
                LOG.log(System.Logger.Level.WARNING, "Consulta do orçamento " + orcamento.id() + " falhou: " + e.getMessage());
            }
        }
        return mudaram;
    }

    /** Webhook: consulta o pagamento informado e aplica. Retorna se algo mudou. */
    public boolean processarNotificacao(String idPagamento) {
        Optional<SituacaoPagamento> situacao = provedor.consultarPagamento(idPagamento);
        if (situacao.isEmpty() || situacao.get().referenciaExterna() == null) {
            return false;
        }
        Optional<Orcamento> orcamento;
        try {
            orcamento = orcamentos.buscarPorId(UUID.fromString(situacao.get().referenciaExterna()));
        } catch (IllegalArgumentException e) {
            return false; // pagamento de outra integração da mesma conta
        }
        return orcamento.isPresent() && aplicar(orcamento.get(), situacao);
    }

    private boolean aplicar(Orcamento orcamento, Optional<SituacaoPagamento> situacao) {
        OffsetDateTime agora = OffsetDateTime.now(relogio);
        List<EventoBilling> eventos = new ArrayList<>();
        boolean mudou = false;

        if (situacao.isPresent()) {
            switch (orcamento.registrarSituacao(situacao.get())) {
                case CONFIRMADO -> {
                    eventos.add(EventoBilling.pagamentoConfirmado(orcamento));
                    metricas.pagamento("CONFIRMADO");
                    mudou = true;
                }
                case RECUSADO -> {
                    eventos.add(EventoBilling.pagamentoRecusado(orcamento));
                    metricas.pagamento("RECUSADO");
                    mudou = true;
                }
                case ESTORNO_NECESSARIO -> {
                    estornoAutomatico(orcamento, agora);
                    mudou = true;
                }
                case NADA_MUDOU -> { /* pendente ou já tratado */ }
            }
        }
        if (!mudou && orcamento.expirarSeVencido(agora)) {
            eventos.add(EventoBilling.pagamentoExpirado(orcamento));
            metricas.pagamento("EXPIRADO");
            mudou = true;
        }
        if (mudou) {
            orcamentos.salvar(orcamento, eventos, null);
        }
        return mudou;
    }

    /** Pago depois de cancelado: devolve na hora. A saga já terminou — não há evento para ela. */
    private void estornoAutomatico(Orcamento orcamento, OffsetDateTime agora) {
        try {
            String ref = provedor.estornar(orcamento.pagamento().idPagamentoProvedor(), "estorno-" + orcamento.id());
            orcamento.registrarEstorno(ref, "PAGO_APOS_CANCELAMENTO", agora);
            metricas.pagamento("ESTORNADO");
            LOG.log(System.Logger.Level.WARNING, "Pagamento aprovado para orçamento cancelado da OS "
                    + orcamento.osId() + " — estornado automaticamente");
        } catch (ProvedorPagamentoGateway.FalhaProvedorException e) {
            orcamento.registrarFalhaDeEstorno(e.getMessage(), agora);
            metricas.falhaIntegracao("mercadopago-estorno");
            // Fica APROVADO num orçamento CANCELADO: a próxima consulta tenta de novo.
        }
    }
}
