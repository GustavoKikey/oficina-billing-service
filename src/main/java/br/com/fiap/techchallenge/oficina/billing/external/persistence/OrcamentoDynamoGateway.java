package br.com.fiap.techchallenge.oficina.billing.external.persistence;

import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.Orcamento;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.OrcamentoGateway;
import br.com.fiap.techchallenge.oficina.billing.shared.entities.OrdemServicoId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tabela única do Billing (ADR 009):
 * <pre>
 *   Orçamento  PK=OS#&lt;osId&gt;  SK=ORCAMENTO          GSI1PK=ORC#&lt;orcamentoId&gt;
 *                                                  GSI2PK=PAG#PENDENTE, GSI2SK=expiraEm (só pendentes)
 *   Outbox     PK=OUTBOX       SK=&lt;epoch-ms&gt;#&lt;messageId&gt;
 * </pre>
 * Um orçamento por OS: a chave pela OS dá leitura consistente e idempotência de graça
 * ({@code attribute_not_exists(PK)} na criação). Concorrência otimista pelo atributo
 * {@code versao}; estado e outbox no mesmo {@code TransactWriteItems}.
 */
@ApplicationScoped
public class OrcamentoDynamoGateway implements OrcamentoGateway {

    static final String PK = "PK";
    static final String SK = "SK";
    static final String OUTBOX = "OUTBOX";
    static final String ORIGEM = "billing-service";

    private final DynamoDbClient dynamo;
    private final ObjectMapper json;
    private final Clock relogio;
    private final String tabela;

    public OrcamentoDynamoGateway(DynamoDbClient dynamo, ObjectMapper json, Clock relogio,
                                  @ConfigProperty(name = "oficina.billing.tabela") String tabela) {
        this.dynamo = dynamo;
        this.json = json;
        this.relogio = relogio;
        this.tabela = tabela;
    }

    @Override
    public Optional<Orcamento> buscarPorOs(OrdemServicoId osId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(tabela).consistentRead(true)
                .key(chave(osId))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(paraDominio(item));
    }

    @Override
    public Optional<Orcamento> buscarPorId(UUID orcamentoId) {
        return dynamo.query(r -> r.tableName(tabela).indexName("GSI1")
                        .keyConditionExpression("GSI1PK = :pk")
                        .expressionAttributeValues(Map.of(":pk", s("ORC#" + orcamentoId))))
                .items().stream().findFirst()
                .flatMap(resumo -> buscarPorOs(OrdemServicoId.de(UUID.fromString(resumo.get("osId").s()))));
    }

    @Override
    public List<Orcamento> comPagamentoPendente(int limite) {
        return dynamo.query(r -> r.tableName(tabela).indexName("GSI2")
                        .keyConditionExpression("GSI2PK = :pk")
                        .expressionAttributeValues(Map.of(":pk", s("PAG#PENDENTE")))
                        .scanIndexForward(true)
                        .limit(limite))
                .items().stream()
                .map(resumo -> buscarPorOs(OrdemServicoId.de(UUID.fromString(resumo.get("osId").s()))))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void salvar(Orcamento o, List<EventoBilling> eventos, UUID causationId) {
        List<TransactWriteItem> escritas = new ArrayList<>();
        escritas.add(TransactWriteItem.builder().put(putOrcamento(o)).build());
        escritas.addAll(outbox(eventos, o.osId(), causationId));
        try {
            dynamo.transactWriteItems(r -> r.transactItems(escritas));
        } catch (TransactionCanceledException e) {
            throw new ConflitoDeVersaoException("Orçamento da OS " + o.osId() + " foi alterado por outra réplica", e);
        }
    }

    @Override
    public void publicar(List<EventoBilling> eventos, OrdemServicoId correlationId, UUID causationId) {
        if (!eventos.isEmpty()) {
            dynamo.transactWriteItems(r -> r.transactItems(outbox(eventos, correlationId, causationId)));
        }
    }

    // ---------------------------------------------------------------------

    private Put putOrcamento(Orcamento o) {
        Map<String, AttributeValue> item = new HashMap<>(chave(o.osId()));
        long novaVersao = o.versao() + 1;
        item.put("osId", s(o.osId().valor().toString()));
        item.put("orcamentoId", s(o.id().toString()));
        item.put("status", s(o.status().name()));
        item.put("versao", AttributeValue.fromN(Long.toString(novaVersao)));
        item.put("dados", s(escrever(OrcamentoDocumento.de(o))));
        item.put("GSI1PK", s("ORC#" + o.id()));
        item.put("GSI1SK", s("ORCAMENTO"));
        if (o.pagamento() != null && o.pagamento().pendente()) {
            // Índice esparso: só pendentes aparecem na consulta periódica.
            item.put("GSI2PK", s("PAG#PENDENTE"));
            item.put("GSI2SK", s(String.valueOf(o.pagamento().expiraEm() == null
                    ? o.pagamento().criadoEm() : o.pagamento().expiraEm())));
        }
        Put.Builder put = Put.builder().tableName(tabela).item(item);
        if (o.versao() == 0) {
            put.conditionExpression("attribute_not_exists(PK)");
        } else {
            put.conditionExpression("versao = :v")
                    .expressionAttributeValues(Map.of(":v", AttributeValue.fromN(Long.toString(o.versao()))));
        }
        return put.build();
    }

    private List<TransactWriteItem> outbox(List<EventoBilling> eventos, OrdemServicoId osId, UUID causationId) {
        OffsetDateTime agora = OffsetDateTime.now(relogio);
        String headers = escrever(contextoDeTrace());
        List<TransactWriteItem> escritas = new ArrayList<>();
        long ordem = agora.toInstant().toEpochMilli() * 1000;
        for (EventoBilling evento : eventos) {
            UUID messageId = UUID.randomUUID();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("messageId", messageId.toString());
            envelope.put("type", evento.tipo());
            envelope.put("version", 1);
            envelope.put("correlationId", osId.valor().toString());
            envelope.put("causationId", causationId == null ? null : causationId.toString());
            envelope.put("occurredAt", agora.toInstant().toString());
            envelope.put("source", ORIGEM);
            envelope.put("payload", evento.payload());

            Map<String, AttributeValue> item = new HashMap<>();
            item.put(PK, s(OUTBOX));
            // Ordenação por tempo + sequência local: eventos do mesmo passo saem na ordem gravada.
            item.put(SK, s(String.format("%019d#%s", ordem++, messageId)));
            item.put("messageId", s(messageId.toString()));
            item.put("tipo", s(evento.tipo()));
            item.put("routingKey", s(evento.routingKey()));
            item.put("correlationId", s(osId.valor().toString()));
            item.put("envelope", s(escrever(envelope)));
            item.put("headers", s(headers));
            escritas.add(TransactWriteItem.builder().put(Put.builder().tableName(tabela).item(item).build()).build());
        }
        return escritas;
    }

    private Orcamento paraDominio(Map<String, AttributeValue> item) {
        try {
            OrcamentoDocumento doc = json.readValue(item.get("dados").s(), OrcamentoDocumento.class);
            return doc.paraDominio(Long.parseLong(item.get("versao").n()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Orçamento ilegível na tabela " + tabela, e);
        }
    }

    private static Map<String, AttributeValue> chave(OrdemServicoId osId) {
        return Map.of(PK, s("OS#" + osId.valor()), SK, s("ORCAMENTO"));
    }

    private static Map<String, String> contextoDeTrace() {
        Map<String, String> headers = new LinkedHashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, Map::put);
        return headers;
    }

    private String escrever(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar para o DynamoDB", e);
        }
    }

    static AttributeValue s(String valor) {
        return AttributeValue.fromS(valor);
    }
}
