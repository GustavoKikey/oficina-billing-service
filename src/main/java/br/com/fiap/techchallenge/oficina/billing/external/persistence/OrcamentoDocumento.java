package br.com.fiap.techchallenge.oficina.billing.external.persistence;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Pagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.StatusOrcamento;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Forma serializada do agregado no atributo {@code dados} do item (JSON). Documento é o
 * que o DynamoDB faz bem: orçamento, itens e pagamento com histórico num item só, lido
 * sempre pela chave (ADR 009). O domínio não conhece Jackson.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OrcamentoDocumento(UUID id, UUID osId, ClienteDoc cliente, List<ItemDoc> itens, OffsetDateTime geradoEm,
                          String status, String aprovadoPor, OffsetDateTime aprovadoEm, String motivoCancelamento,
                          OffsetDateTime canceladoEm, PagamentoDoc pagamento) {

    record ClienteDoc(String id, String nome, String email) {}

    record ItemDoc(String tipo, String referenciaId, String descricao, int quantidade, BigDecimal valorUnitario) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PagamentoDoc(UUID id, String referenciaProvedor, String link, BigDecimal valor, OffsetDateTime criadoEm,
                        OffsetDateTime expiraEm, String status, String idPagamentoProvedor, String detalhe,
                        OffsetDateTime confirmadoEm, OffsetDateTime estornadoEm, List<String> historico) {}

    static OrcamentoDocumento de(Orcamento o) {
        Pagamento p = o.pagamento();
        return new OrcamentoDocumento(o.id(), o.osId().valor(),
                o.cliente() == null ? null : new ClienteDoc(o.cliente().id(), o.cliente().nome(), o.cliente().email()),
                o.itens().stream().map(i -> new ItemDoc(i.tipo(), i.referenciaId(), i.descricao(), i.quantidade(),
                        i.valorUnitario().valor())).toList(),
                o.geradoEm(), o.status().name(), o.aprovadoPor(), o.aprovadoEm(), o.motivoCancelamento(),
                o.canceladoEm(),
                p == null ? null : new PagamentoDoc(p.id(), p.referenciaProvedor(), p.link(), p.valor().valor(),
                        p.criadoEm(), p.expiraEm(), p.status().name(), p.idPagamentoProvedor(), p.detalhe(),
                        p.confirmadoEm(), p.estornadoEm(), p.historico()));
    }

    Orcamento paraDominio(long versao) {
        Orcamento.Dados d = new Orcamento.Dados();
        d.id = id;
        d.osId = OrdemServicoId.de(osId);
        d.cliente = cliente == null ? null : new Orcamento.Cliente(cliente.id(), cliente.nome(), cliente.email());
        d.itens = itens.stream().map(i -> new Orcamento.Item(i.tipo(), i.referenciaId(), i.descricao(), i.quantidade(),
                Dinheiro.de(i.valorUnitario()))).toList();
        d.geradoEm = geradoEm;
        d.status = StatusOrcamento.valueOf(status);
        d.aprovadoPor = aprovadoPor;
        d.aprovadoEm = aprovadoEm;
        d.motivoCancelamento = motivoCancelamento;
        d.canceladoEm = canceladoEm;
        d.versao = versao;
        if (pagamento != null) {
            Pagamento.Dados pd = new Pagamento.Dados();
            pd.id = pagamento.id();
            pd.referenciaProvedor = pagamento.referenciaProvedor();
            pd.link = pagamento.link();
            pd.valor = Dinheiro.de(pagamento.valor());
            pd.criadoEm = pagamento.criadoEm();
            pd.expiraEm = pagamento.expiraEm();
            pd.status = Pagamento.Status.valueOf(pagamento.status());
            pd.idPagamentoProvedor = pagamento.idPagamentoProvedor();
            pd.detalhe = pagamento.detalhe();
            pd.confirmadoEm = pagamento.confirmadoEm();
            pd.estornadoEm = pagamento.estornadoEm();
            pd.historico = pagamento.historico();
            d.pagamento = Pagamento.reconstituir(pd);
        }
        return Orcamento.reconstituir(d);
    }
}
