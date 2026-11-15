package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Evento que o Billing publica para o orquestrador ({@code contratos-mensagens.md} §4).
 * Payload em valores simples; dinheiro como texto decimal; instantes em ISO-8601.
 */
public record EventoBilling(String tipo, Map<String, Object> payload) {

    public EventoBilling {
        Objects.requireNonNull(tipo, "tipo");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Routing key no exchange {@code oficina.eventos}: {@code billing.<evento-em-kebab-case>}. */
    public String routingKey() {
        return "billing." + tipo.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    public static EventoBilling orcamentoGerado(Orcamento o) {
        return evento("OrcamentoGerado", o, "valorTotal", texto(o), "geradoEm", o.geradoEm().toString());
    }

    public static EventoBilling orcamentoNaoGerado(String osId, String motivo) {
        return new EventoBilling("OrcamentoNaoGerado", mapa("osId", osId, "motivo", motivo));
    }

    public static EventoBilling pagamentoSolicitado(Orcamento o) {
        Pagamento p = o.pagamento();
        return evento("PagamentoSolicitado", o, "pagamentoId", p.id().toString(), "linkPagamento", p.link(),
                "expiraEm", p.expiraEm() == null ? null : p.expiraEm().toString());
    }

    public static EventoBilling pagamentoConfirmado(Orcamento o) {
        Pagamento p = o.pagamento();
        return evento("PagamentoConfirmado", o, "pagamentoId", p.id().toString(),
                "valorPago", p.valor().valor().toPlainString(), "confirmadoEm", p.confirmadoEm().toString());
    }

    public static EventoBilling pagamentoRecusado(Orcamento o) {
        return evento("PagamentoRecusado", o, "pagamentoId", o.pagamento().id().toString(),
                "motivo", o.pagamento().detalhe());
    }

    public static EventoBilling pagamentoExpirado(Orcamento o) {
        return evento("PagamentoExpirado", o, "pagamentoId", o.pagamento().id().toString());
    }

    public static EventoBilling orcamentoCancelado(String osId, String orcamentoId) {
        return new EventoBilling("OrcamentoCancelado", mapa("osId", osId, "orcamentoId", orcamentoId));
    }

    public static EventoBilling pagamentoEstornado(Orcamento o, String valorEstornado) {
        Pagamento p = o.pagamento();
        return evento("PagamentoEstornado", o,
                "pagamentoId", p == null ? null : p.id().toString(),
                "valorEstornado", valorEstornado,
                "estornadoEm", p == null || p.estornadoEm() == null ? null : p.estornadoEm().toString());
    }

    public static EventoBilling estornoFalhou(Orcamento o, String erro) {
        return evento("EstornoFalhou", o, "pagamentoId", o.pagamento().id().toString(), "erro", erro);
    }

    private static String texto(Orcamento o) {
        return o.valorTotal().valor().toPlainString();
    }

    private static EventoBilling evento(String tipo, Orcamento o, Object... campos) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("osId", o.osId().valor().toString());
        payload.put("orcamentoId", o.id().toString());
        payload.putAll(mapa(campos));
        return new EventoBilling(tipo, payload);
    }

    static Map<String, Object> mapa(Object... chaveValor) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < chaveValor.length; i += 2) {
            m.put((String) chaveValor[i], chaveValor[i + 1]);
        }
        return m;
    }
}
