package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

import br.com.fiap.techchallenge.oficina.billing.shared.entities.Dinheiro;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A cobrança de um orçamento no provedor (Mercado Pago). Uma por orçamento: pagamento
 * recusado ou expirado cancela a OS, não abre nova tentativa (escopo da fase 4).
 *
 * <p>Guarda o histórico do que o provedor informou — útil para auditoria e para explicar
 * um estorno automático.
 */
public class Pagamento {

    public enum Status { PENDENTE, APROVADO, RECUSADO, EXPIRADO, CANCELADO, ESTORNADO }

    private final UUID id;
    private final String referenciaProvedor;
    private final String link;
    private final Dinheiro valor;
    private final OffsetDateTime criadoEm;
    private final OffsetDateTime expiraEm;
    private Status status;
    private String idPagamentoProvedor;
    private String detalhe;
    private OffsetDateTime confirmadoEm;
    private OffsetDateTime estornadoEm;
    private final List<String> historico;

    private Pagamento(UUID id, String referenciaProvedor, String link, Dinheiro valor, OffsetDateTime criadoEm,
                      OffsetDateTime expiraEm, Status status, List<String> historico) {
        this.id = Objects.requireNonNull(id, "id");
        this.referenciaProvedor = referenciaProvedor;
        this.link = Objects.requireNonNull(link, "link");
        this.valor = Objects.requireNonNull(valor, "valor");
        this.criadoEm = Objects.requireNonNull(criadoEm, "criadoEm");
        this.expiraEm = expiraEm;
        this.status = Objects.requireNonNull(status, "status");
        this.historico = new ArrayList<>(historico);
    }

    static Pagamento novo(CobrancaCriada cobranca, Dinheiro valor, OffsetDateTime agora) {
        Pagamento p = new Pagamento(UUID.randomUUID(), cobranca.referencia(), cobranca.link(), valor, agora,
                cobranca.expiraEm(), Status.PENDENTE, List.of());
        p.registrar(agora, "cobrança criada no provedor (" + cobranca.referencia() + ")");
        return p;
    }

    public static Pagamento reconstituir(Dados d) {
        Pagamento p = new Pagamento(d.id, d.referenciaProvedor, d.link, d.valor, d.criadoEm, d.expiraEm, d.status,
                d.historico == null ? List.of() : d.historico);
        p.idPagamentoProvedor = d.idPagamentoProvedor;
        p.detalhe = d.detalhe;
        p.confirmadoEm = d.confirmadoEm;
        p.estornadoEm = d.estornadoEm;
        return p;
    }

    void aprovar(SituacaoPagamento s) {
        idPagamentoProvedor = s.idPagamento();
        detalhe = s.detalhe();
        confirmadoEm = s.quando();
        status = Status.APROVADO;
        registrar(s.quando(), "aprovado (" + s.idPagamento() + ")");
    }

    void recusar(SituacaoPagamento s) {
        idPagamentoProvedor = s.idPagamento();
        detalhe = s.detalhe();
        status = Status.RECUSADO;
        registrar(s.quando(), "recusado: " + s.detalhe());
    }

    void expirar(OffsetDateTime agora) {
        status = Status.EXPIRADO;
        registrar(agora, "link venceu sem pagamento aprovado");
    }

    void cancelar(OffsetDateTime agora) {
        status = Status.CANCELADO;
        registrar(agora, "cobrança cancelada");
    }

    void estornar(String referenciaEstorno, OffsetDateTime agora) {
        status = Status.ESTORNADO;
        estornadoEm = agora;
        registrar(agora, "estornado (" + referenciaEstorno + ")");
    }

    void registrar(OffsetDateTime quando, String texto) {
        historico.add(quando + " " + texto);
    }

    public boolean pendente()                { return status == Status.PENDENTE; }
    public boolean vencidoEm(OffsetDateTime agora) {
        return pendente() && expiraEm != null && !expiraEm.isAfter(agora);
    }

    public UUID id()                         { return id; }
    public String referenciaProvedor()       { return referenciaProvedor; }
    public String link()                     { return link; }
    public Dinheiro valor()                  { return valor; }
    public OffsetDateTime criadoEm()         { return criadoEm; }
    public OffsetDateTime expiraEm()         { return expiraEm; }
    public Status status()                   { return status; }
    public String idPagamentoProvedor()      { return idPagamentoProvedor; }
    public String detalhe()                  { return detalhe; }
    public OffsetDateTime confirmadoEm()     { return confirmadoEm; }
    public OffsetDateTime estornadoEm()      { return estornadoEm; }
    public List<String> historico()          { return List.copyOf(historico); }

    public static final class Dados {
        public UUID id;
        public String referenciaProvedor;
        public String link;
        public Dinheiro valor;
        public OffsetDateTime criadoEm;
        public OffsetDateTime expiraEm;
        public Status status;
        public String idPagamentoProvedor;
        public String detalhe;
        public OffsetDateTime confirmadoEm;
        public OffsetDateTime estornadoEm;
        public List<String> historico;
    }
}
