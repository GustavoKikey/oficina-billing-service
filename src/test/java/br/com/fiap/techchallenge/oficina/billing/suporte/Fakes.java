package br.com.fiap.techchallenge.oficina.billing.suporte;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.CobrancaCriada;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.SituacaoPagamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.OrcamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class Fakes {

    public static final OffsetDateTime AGORA = OffsetDateTime.of(2026, 11, 15, 14, 0, 0, 0, ZoneOffset.UTC);

    private Fakes() {}

    /** Persistência em memória que guarda o agregado "como gravado" e os eventos do outbox. */
    public static final class Orcamentos implements OrcamentoGateway {
        public final Map<OrdemServicoId, Orcamento> dados = new LinkedHashMap<>();
        public final List<EventoBilling> outbox = new ArrayList<>();
        public final List<UUID> causas = new ArrayList<>();

        @Override public Optional<Orcamento> buscarPorOs(OrdemServicoId osId) { return Optional.ofNullable(dados.get(osId)); }
        @Override public Optional<Orcamento> buscarPorId(UUID id) {
            return dados.values().stream().filter(o -> o.id().equals(id)).findFirst();
        }
        @Override public List<Orcamento> comPagamentoPendente(int limite) {
            return dados.values().stream().filter(o -> o.pagamento() != null && o.pagamento().pendente())
                    .sorted(Comparator.comparing(o -> o.pagamento().criadoEm())).limit(limite).toList();
        }
        @Override public void salvar(Orcamento o, List<EventoBilling> eventos, UUID causa) {
            dados.put(o.osId(), o);
            outbox.addAll(eventos);
            eventos.forEach(e -> causas.add(causa));
        }
        @Override public void publicar(List<EventoBilling> eventos, OrdemServicoId correlationId, UUID causa) {
            outbox.addAll(eventos);
            eventos.forEach(e -> causas.add(causa));
        }

        public List<String> tipos() {
            return outbox.stream().map(EventoBilling::tipo).toList();
        }

        public EventoBilling ultimo() {
            return outbox.get(outbox.size() - 1);
        }
    }

    /** Provedor controlável: o teste decide o que a "API do Mercado Pago" responde e quando falha. */
    public static final class Provedor implements ProvedorPagamentoGateway {
        public final Map<String, SituacaoPagamento> porOrcamento = new LinkedHashMap<>();
        public final List<String> estornos = new ArrayList<>();
        public int falhasDeEstornoRestantes;
        public boolean consultaFalha;

        @Override
        public CobrancaCriada criarCobranca(Orcamento o, OffsetDateTime expiraEm) {
            return new CobrancaCriada("pref-" + o.id(), "https://mp.test/checkout/" + o.id(), expiraEm);
        }

        public SituacaoPagamento pagar(Orcamento o, SituacaoPagamento.Situacao situacao, OffsetDateTime quando) {
            SituacaoPagamento s = new SituacaoPagamento("pag-" + o.id(), o.id().toString(), situacao,
                    situacao == SituacaoPagamento.Situacao.RECUSADO ? "cc_rejected_other_reason" : "accredited",
                    o.valorTotal(), quando);
            porOrcamento.put(o.id().toString(), s);
            return s;
        }

        @Override
        public Optional<SituacaoPagamento> consultarPorOrcamento(Orcamento o) {
            if (consultaFalha) {
                throw new FalhaProvedorException("Mercado Pago fora do ar");
            }
            return Optional.ofNullable(porOrcamento.get(o.id().toString()));
        }

        @Override
        public Optional<SituacaoPagamento> consultarPagamento(String id) {
            return porOrcamento.values().stream().filter(s -> s.idPagamento().equals(id)).findFirst();
        }

        @Override
        public String estornar(String idPagamento, String chave) {
            if (falhasDeEstornoRestantes > 0) {
                falhasDeEstornoRestantes--;
                throw new FalhaProvedorException("503 no estorno");
            }
            estornos.add(idPagamento);
            return "refund-" + chave;
        }
    }

    public static final class Metricas implements MetricasGateway {
        public final List<String> registros = new ArrayList<>();

        @Override public void pagamento(String status) { registros.add("pagamento:" + status); }
        @Override public void falhaIntegracao(String i) { registros.add("falha:" + i); }
        @Override public void falhaProcessamento(String o) { registros.add("processamento:" + o); }
    }

    public static final class Relogio extends Clock {
        private Instant agora = AGORA.toInstant();

        public void avancar(Duration d) { agora = agora.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return agora; }
    }
}
