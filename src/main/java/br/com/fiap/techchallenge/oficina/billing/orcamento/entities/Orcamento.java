package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregado raiz <b>Orçamento</b> (Billing Service): valor calculado a partir dos itens do
 * diagnóstico, aprovação do cliente e a cobrança no Mercado Pago.
 *
 * <p>Um por OS. O valor cobrado vem <b>sempre daqui</b>, nunca de uma mensagem (regra 5
 * do contrato): mesmo que alguém publique um comando com outro valor, a cobrança é do
 * orçamento calculado.
 */
public class Orcamento {

    /** Dados do cliente para a cobrança e o e-mail — vêm no comando GerarOrcamento. */
    public record Cliente(String id, String nome, String email) {}

    /** Item como a Execução registrou no diagnóstico. */
    public record Item(String tipo, String referenciaId, String descricao, int quantidade, Dinheiro valorUnitario) {

        public Item {
            if (descricao == null || descricao.isBlank()) {
                throw new OrcamentoInvalidoException("Item sem descrição");
            }
            if (quantidade <= 0) {
                throw new OrcamentoInvalidoException("Quantidade inválida em '" + descricao + "': " + quantidade);
            }
            // Negativo não chega aqui: Dinheiro não admite (e o use case traduz o texto inválido).
            if (valorUnitario == null) {
                throw new OrcamentoInvalidoException("Valor ausente em '" + descricao + "'");
            }
        }

        public Dinheiro subtotal() {
            return valorUnitario.multiplicar(quantidade);
        }
    }

    /** O que mudou num pagamento, do ponto de vista da saga. */
    public enum Desfecho { CONFIRMADO, RECUSADO, NADA_MUDOU, ESTORNO_NECESSARIO }

    private final UUID id;
    private final OrdemServicoId osId;
    private final Cliente cliente;
    private final List<Item> itens;
    private final Dinheiro valorTotal;
    private final OffsetDateTime geradoEm;
    private StatusOrcamento status;
    private String aprovadoPor;
    private OffsetDateTime aprovadoEm;
    private String motivoCancelamento;
    private OffsetDateTime canceladoEm;
    private Pagamento pagamento;
    private final long versao;

    private Orcamento(UUID id, OrdemServicoId osId, Cliente cliente, List<Item> itens, OffsetDateTime geradoEm,
                      StatusOrcamento status, long versao) {
        this.id = Objects.requireNonNull(id, "id");
        this.osId = Objects.requireNonNull(osId, "osId");
        this.cliente = cliente;
        this.itens = List.copyOf(itens);
        this.geradoEm = Objects.requireNonNull(geradoEm, "geradoEm");
        this.status = Objects.requireNonNull(status, "status");
        this.versao = versao;
        Dinheiro total = Dinheiro.ZERO;
        for (Item i : this.itens) {
            total = total.somar(i.subtotal());
        }
        this.valorTotal = total;
    }

    /** @throws OrcamentoInvalidoException sem itens — a saga recebe {@code OrcamentoNaoGerado} */
    public static Orcamento gerar(OrdemServicoId osId, Cliente cliente, List<Item> itens, OffsetDateTime agora) {
        if (itens == null || itens.isEmpty()) {
            throw new OrcamentoInvalidoException("Diagnóstico sem itens: não há o que orçar");
        }
        return new Orcamento(UUID.randomUUID(), osId, cliente, itens, agora, StatusOrcamento.GERADO, 0);
    }

    // ---------- Pagamento ----------

    /** Cliente aprovou: registra a aprovação e a cobrança criada no provedor. */
    public void solicitarPagamento(String por, OffsetDateTime aprovadoEmQuando, CobrancaCriada cobranca,
                                   OffsetDateTime agora) {
        exigir(StatusOrcamento.GERADO, "solicitar pagamento");
        this.aprovadoPor = por;
        this.aprovadoEm = aprovadoEmQuando;
        this.pagamento = Pagamento.novo(cobranca, valorTotal, agora);
        this.status = StatusOrcamento.AGUARDANDO_PAGAMENTO;
    }

    /**
     * Aplica o que o provedor informou. Pagamento aprovado de orçamento já cancelado (o
     * cliente pagou enquanto a saga expirava o prazo) pede estorno automático — orçamento
     * cancelado nunca fica com dinheiro (RFC 004 §3.4).
     */
    public Desfecho registrarSituacao(SituacaoPagamento s) {
        if (pagamento == null) {
            return Desfecho.NADA_MUDOU;
        }
        if (status == StatusOrcamento.CANCELADO && s.situacao() == SituacaoPagamento.Situacao.APROVADO
                && pagamento.status() != Pagamento.Status.APROVADO && pagamento.status() != Pagamento.Status.ESTORNADO) {
            pagamento.aprovar(s);
            return Desfecho.ESTORNO_NECESSARIO;
        }
        if (status != StatusOrcamento.AGUARDANDO_PAGAMENTO || !pagamento.pendente()) {
            return Desfecho.NADA_MUDOU;
        }
        return switch (s.situacao()) {
            case APROVADO -> {
                pagamento.aprovar(s);
                status = StatusOrcamento.PAGO;
                yield Desfecho.CONFIRMADO;
            }
            case RECUSADO -> {
                pagamento.recusar(s);
                yield Desfecho.RECUSADO;
            }
            case PENDENTE -> Desfecho.NADA_MUDOU;
        };
    }

    /** @return {@code true} se o link venceu agora — a saga recebe {@code PagamentoExpirado} */
    public boolean expirarSeVencido(OffsetDateTime agora) {
        if (status == StatusOrcamento.AGUARDANDO_PAGAMENTO && pagamento != null && pagamento.vencidoEm(agora)) {
            pagamento.expirar(agora);
            return true;
        }
        return false;
    }

    /** Compensação. Já cancelado: nada a fazer. Pago não se cancela — estorna. */
    public void cancelar(String motivo, OffsetDateTime agora) {
        if (status == StatusOrcamento.CANCELADO || status == StatusOrcamento.ESTORNADO) {
            return;
        }
        if (status == StatusOrcamento.PAGO) {
            throw new IllegalStateException("Orçamento " + id + " já foi pago: use EstornarPagamento");
        }
        if (pagamento != null && pagamento.pendente()) {
            pagamento.cancelar(agora);
        }
        this.motivoCancelamento = motivo;
        this.canceladoEm = agora;
        this.status = StatusOrcamento.CANCELADO;
    }

    /** Pagamento aprovado que precisa voltar ao cliente: compensação ou estorno automático. */
    public boolean precisaDeEstorno() {
        return pagamento != null && pagamento.status() == Pagamento.Status.APROVADO;
    }

    public void registrarEstorno(String referenciaEstorno, String motivo, OffsetDateTime agora) {
        if (!precisaDeEstorno()) {
            throw new IllegalStateException("Orçamento " + id + " não tem pagamento aprovado para estornar");
        }
        pagamento.estornar(referenciaEstorno, agora);
        if (status != StatusOrcamento.CANCELADO) {
            this.motivoCancelamento = motivo;
            this.canceladoEm = agora;
        }
        this.status = status == StatusOrcamento.CANCELADO ? StatusOrcamento.CANCELADO : StatusOrcamento.ESTORNADO;
    }

    public void registrarFalhaDeEstorno(String erro, OffsetDateTime agora) {
        if (pagamento != null) {
            pagamento.registrar(agora, "estorno falhou: " + erro);
        }
    }

    private void exigir(StatusOrcamento esperado, String acao) {
        if (status != esperado) {
            throw new IllegalStateException("Não é possível " + acao + " com o orçamento " + id + " em " + status);
        }
    }

    // ---------- Reconstituição e acessores ----------

    public static Orcamento reconstituir(Dados d) {
        Orcamento o = new Orcamento(d.id, d.osId, d.cliente, d.itens, d.geradoEm, d.status, d.versao);
        o.aprovadoPor = d.aprovadoPor;
        o.aprovadoEm = d.aprovadoEm;
        o.motivoCancelamento = d.motivoCancelamento;
        o.canceladoEm = d.canceladoEm;
        o.pagamento = d.pagamento;
        return o;
    }

    public UUID id()                         { return id; }
    public OrdemServicoId osId()             { return osId; }
    public Cliente cliente()                 { return cliente; }
    public List<Item> itens()                { return itens; }
    public Dinheiro valorTotal()             { return valorTotal; }
    public OffsetDateTime geradoEm()         { return geradoEm; }
    public StatusOrcamento status()          { return status; }
    public String aprovadoPor()              { return aprovadoPor; }
    public OffsetDateTime aprovadoEm()       { return aprovadoEm; }
    public String motivoCancelamento()       { return motivoCancelamento; }
    public OffsetDateTime canceladoEm()      { return canceladoEm; }
    public Pagamento pagamento()             { return pagamento; }
    public long versao()                     { return versao; }

    public static final class Dados {
        public UUID id;
        public OrdemServicoId osId;
        public Cliente cliente;
        public List<Item> itens = List.of();
        public OffsetDateTime geradoEm;
        public StatusOrcamento status;
        public String aprovadoPor;
        public OffsetDateTime aprovadoEm;
        public String motivoCancelamento;
        public OffsetDateTime canceladoEm;
        public Pagamento pagamento;
        public long versao;
    }
}
