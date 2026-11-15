package br.com.fiap.techchallenge.oficina.billing.external.messaging;

import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.TracingMetadata;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

/**
 * Relay do outbox no DynamoDB (ADR 010): lê a partição {@code OUTBOX} em ordem, publica
 * com publisher confirms e <b>apaga</b> o item só depois da confirmação. Para no primeiro
 * erro, para não inverter a ordem dos eventos.
 *
 * <p>Diferente do PostgreSQL, não há {@code SKIP LOCKED}: duas réplicas podem publicar o
 * mesmo item numa corrida. É entrega pelo menos uma vez, e o orquestrador deduplica pelo
 * {@code messageId} (inbox) — o contrato já conta com isso.
 */
@ApplicationScoped
public class RelayOutbox {

    private static final Logger LOG = Logger.getLogger(RelayOutbox.class);
    private static final TypeReference<Map<String, String>> MAPA = new TypeReference<>() {};
    private static final TextMapGetter<Map<String, String>> LEITOR = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final MutinyEmitter<String> emissor;
    private final DynamoDbClient dynamo;
    private final ObjectMapper json;
    private final MetricasGateway metricas;
    private final String tabela;
    private final int lote;

    public RelayOutbox(@Channel("eventos") MutinyEmitter<String> emissor, DynamoDbClient dynamo, ObjectMapper json,
                       MetricasGateway metricas, @ConfigProperty(name = "oficina.billing.tabela") String tabela,
                       @ConfigProperty(name = "oficina.outbox.lote", defaultValue = "100") int lote) {
        this.emissor = emissor;
        this.dynamo = dynamo;
        this.json = json;
        this.metricas = metricas;
        this.tabela = tabela;
        this.lote = lote;
    }

    @Scheduled(every = "${oficina.outbox.intervalo:1s}", delayed = "${oficina.outbox.atraso-inicial:2s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void agendado() {
        try {
            publicarPendentes();
        } catch (RuntimeException e) {
            LOG.warnf("Relay do outbox falhou (%s) — nova tentativa na próxima passada", e.getMessage());
        }
    }

    public int publicarPendentes() {
        List<Map<String, AttributeValue>> pendentes = dynamo.query(r -> r.tableName(tabela)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("OUTBOX")))
                .consistentRead(true)
                .scanIndexForward(true)
                .limit(lote)).items();
        int publicadas = 0;
        for (Map<String, AttributeValue> item : pendentes) {
            try {
                publicar(item);
            } catch (RuntimeException e) {
                LOG.warnf("Falha ao publicar %s: %s — nova tentativa na próxima passada", item.get("tipo").s(),
                        e.getMessage());
                metricas.falhaIntegracao("rabbitmq-publicacao");
                break;
            }
            dynamo.deleteItem(r -> r.tableName(tabela).key(Map.of("PK", item.get("PK"), "SK", item.get("SK"))));
            publicadas++;
        }
        return publicadas;
    }

    private void publicar(Map<String, AttributeValue> item) {
        OutgoingRabbitMQMetadata rabbit = OutgoingRabbitMQMetadata.builder()
                .withRoutingKey(item.get("routingKey").s())
                .withMessageId(item.get("messageId").s())
                .withCorrelationId(item.get("correlationId").s())
                .withType(item.get("tipo").s())
                .withContentType("application/json")
                .withDeliveryMode(2)
                .build();
        emissor.sendMessageAndAwait(Message.of(item.get("envelope").s(),
                Metadata.of(rabbit, TracingMetadata.withPrevious(contexto(item)))));
    }

    private Context contexto(Map<String, AttributeValue> item) {
        try {
            Map<String, String> headers = json.readValue(item.get("headers").s(), MAPA);
            return W3CTraceContextPropagator.getInstance().extract(Context.root(), headers, LEITOR);
        } catch (Exception e) {
            return Context.root();
        }
    }
}
