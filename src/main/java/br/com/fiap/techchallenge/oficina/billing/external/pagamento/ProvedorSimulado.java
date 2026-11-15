package br.com.fiap.techchallenge.oficina.billing.external.pagamento;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.CobrancaCriada;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provedor de pagamento simulado — ambiente local, testes e ensaio da demonstração, sem
 * credenciais do Mercado Pago. Ligado por {@code PAGAMENTO_PROVEDOR=simulado}.
 *
 * <p>O "link de pagamento" aponta para {@code /simulador/checkout/{orcamentoId}} deste
 * serviço; aprovar ou recusar é um POST em {@code /simulador/pagamentos/{orcamentoId}}.
 * O resto do fluxo (consulta periódica, eventos, estorno) é o mesmo código do Mercado Pago.
 * Estado em memória: some ao reiniciar, como uma sessão de sandbox.
 */
public class ProvedorSimulado implements ProvedorPagamentoGateway {

    private final Map<String, SituacaoPagamento> pagamentos = new ConcurrentHashMap<>();
    private final Map<String, String> estornos = new ConcurrentHashMap<>();
    private final Map<String, Dinheiro> valores = new ConcurrentHashMap<>();
    private final String urlBase;
    private final Clock relogio;

    public ProvedorSimulado(String urlBase, Clock relogio) {
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.relogio = relogio;
    }

    @Override
    public CobrancaCriada criarCobranca(Orcamento orcamento, OffsetDateTime expiraEm) {
        valores.put(orcamento.id().toString(), orcamento.valorTotal());
        return new CobrancaCriada("sim-" + orcamento.id(), urlBase + "/simulador/checkout/" + orcamento.id(), expiraEm);
    }

    /** O "cliente" paga (ou o cartão é recusado) no checkout simulado. */
    public SituacaoPagamento pagar(String orcamentoId, boolean aprovado) {
        SituacaoPagamento s = new SituacaoPagamento("sim-pag-" + UUID.randomUUID(), orcamentoId,
                aprovado ? SituacaoPagamento.Situacao.APROVADO : SituacaoPagamento.Situacao.RECUSADO,
                aprovado ? "accredited" : "cc_rejected_other_reason",
                valores.getOrDefault(orcamentoId, Dinheiro.ZERO), OffsetDateTime.now(relogio));
        pagamentos.put(orcamentoId, s);
        return s;
    }

    public boolean conhece(String orcamentoId) {
        return valores.containsKey(orcamentoId);
    }

    public boolean estornado(String idPagamento) {
        return estornos.containsKey(idPagamento);
    }

    @Override
    public Optional<SituacaoPagamento> consultarPorOrcamento(Orcamento orcamento) {
        return Optional.ofNullable(pagamentos.get(orcamento.id().toString()));
    }

    @Override
    public Optional<SituacaoPagamento> consultarPagamento(String idPagamento) {
        return pagamentos.values().stream().filter(p -> p.idPagamento().equals(idPagamento)).findFirst();
    }

    @Override
    public String estornar(String idPagamento, String chaveIdempotencia) {
        return estornos.computeIfAbsent(idPagamento, id -> "sim-estorno-" + chaveIdempotencia);
    }
}
