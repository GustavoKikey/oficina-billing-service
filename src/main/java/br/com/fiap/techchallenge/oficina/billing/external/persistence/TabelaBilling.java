package br.com.fiap.techchallenge.oficina.billing.external.persistence;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Cria a tabela no DynamoDB Local (ambiente local e testes). Na AWS quem cria é o
 * Terraform de {@code infra/}, e esta classe fica desligada
 * ({@code oficina.billing.criar-tabela=false}).
 */
@ApplicationScoped
public class TabelaBilling {

    private static final Logger LOG = Logger.getLogger(TabelaBilling.class);

    private final DynamoDbClient dynamo;
    private final String tabela;
    private final boolean criar;

    public TabelaBilling(DynamoDbClient dynamo,
                         @ConfigProperty(name = "oficina.billing.tabela") String tabela,
                         @ConfigProperty(name = "oficina.billing.criar-tabela", defaultValue = "false") boolean criar) {
        this.dynamo = dynamo;
        this.tabela = tabela;
        this.criar = criar;
    }

    void aoIniciar(@Observes StartupEvent evento) {
        if (criar) {
            criarSeNaoExistir();
        }
    }

    public void criarSeNaoExistir() {
        try {
            dynamo.createTable(r -> r.tableName(tabela)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            atributo("PK"), atributo("SK"), atributo("GSI1PK"), atributo("GSI1SK"),
                            atributo("GSI2PK"), atributo("GSI2SK"))
                    .keySchema(chave("PK", KeyType.HASH), chave("SK", KeyType.RANGE))
                    .globalSecondaryIndexes(indice("GSI1"), indice("GSI2")));
            LOG.infof("Tabela %s criada", tabela);
        } catch (ResourceInUseException e) {
            LOG.debugf("Tabela %s já existe", tabela);
        }
    }

    private static AttributeDefinition atributo(String nome) {
        return AttributeDefinition.builder().attributeName(nome).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement chave(String nome, KeyType tipo) {
        return KeySchemaElement.builder().attributeName(nome).keyType(tipo).build();
    }

    private static GlobalSecondaryIndex indice(String nome) {
        return GlobalSecondaryIndex.builder().indexName(nome)
                .keySchema(chave(nome + "PK", KeyType.HASH), chave(nome + "SK", KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.INCLUDE)
                        .nonKeyAttributes("osId", "orcamentoId").build())
                .build();
    }
}
