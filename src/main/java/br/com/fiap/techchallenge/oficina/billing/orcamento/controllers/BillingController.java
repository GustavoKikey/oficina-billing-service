package br.com.fiap.techchallenge.oficina.billing.orcamento.controllers;

import br.com.fiap.techchallenge.oficina.billing.orcamento.dtos.OrcamentoResponse;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Pagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.OrcamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.orcamento.usecases.AcompanharPagamentosUseCase;
import br.com.fiap.techchallenge.oficina.billing.orcamento.usecases.ComandosBillingUseCase;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;

import java.util.Optional;
import java.util.UUID;

/**
 * Interface Adapter do Billing. Sem fronteira JTA: a atomicidade vem do
 * {@code TransactWriteItems} do DynamoDB, dentro do gateway.
 */
public class BillingController {

    private final ComandosBillingUseCase comandos;
    private final AcompanharPagamentosUseCase pagamentos;
    private final OrcamentoGateway orcamentos;

    public BillingController(ComandosBillingUseCase comandos, AcompanharPagamentosUseCase pagamentos,
                             OrcamentoGateway orcamentos) {
        this.comandos = comandos;
        this.pagamentos = pagamentos;
        this.orcamentos = orcamentos;
    }

    // ---------- comandos da saga ----------

    public EventoBilling gerarOrcamento(ComandosBillingUseCase.GerarOrcamento cmd) {
        return comandos.gerarOrcamento(cmd);
    }

    public EventoBilling solicitarPagamento(ComandosBillingUseCase.SolicitarPagamento cmd) {
        return comandos.solicitarPagamento(cmd);
    }

    public EventoBilling cancelarOrcamento(ComandosBillingUseCase.CancelarOrcamento cmd) {
        return comandos.cancelarOrcamento(cmd);
    }

    public EventoBilling estornarPagamento(ComandosBillingUseCase.EstornarPagamento cmd) {
        return comandos.estornarPagamento(cmd);
    }

    // ---------- pagamentos ----------

    public int verificarPagamentosPendentes() {
        return pagamentos.verificarPendentes();
    }

    public boolean notificacaoDePagamento(String idPagamento) {
        return pagamentos.processarNotificacao(idPagamento);
    }

    // ---------- consulta ----------

    public Optional<OrcamentoResponse> buscarPorOs(UUID osId) {
        return orcamentos.buscarPorOs(OrdemServicoId.de(osId)).map(BillingController::apresentar);
    }

    static OrcamentoResponse apresentar(Orcamento o) {
        Pagamento p = o.pagamento();
        return new OrcamentoResponse(o.id(), o.osId().valor(), o.status().name(), o.valorTotal().valor(),
                o.itens().stream().map(i -> new OrcamentoResponse.ItemView(i.tipo(), i.referenciaId(), i.descricao(),
                        i.quantidade(), i.valorUnitario().valor(), i.subtotal().valor())).toList(),
                o.geradoEm(), o.aprovadoPor(), o.aprovadoEm(), o.motivoCancelamento(),
                p == null ? null : new OrcamentoResponse.PagamentoView(p.id(), p.status().name(), p.link(),
                        p.valor().valor(), p.expiraEm(), p.idPagamentoProvedor(), p.detalhe(), p.confirmadoEm(),
                        p.estornadoEm(), p.historico()));
    }
}
