package br.com.fiap.techchallenge.oficina.billing;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Sobe o DynamoDB Local oficial da AWS para os testes que iniciam a aplicação.
 *
 * <p>Substitui o Dev Services do Quarkus, que depende do LocalStack (agora exige
 * token de conta). É a mesma imagem usada no docker-compose do ambiente local.
 */
public class DynamoDbLocalResource implements QuarkusTestResourceLifecycleManager {

    static final DockerImageName IMAGEM = DockerImageName.parse("amazon/dynamodb-local:3.3.2");
    private static final int PORTA = 8000;

    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new GenericContainer<>(IMAGEM)
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
                .withExposedPorts(PORTA)
                .waitingFor(Wait.forListeningPort());
        container.start();

        String endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(PORTA);
        return Map.of(
                "quarkus.dynamodb.endpoint-override", endpoint,
                "quarkus.dynamodb.aws.credentials.type", "static",
                "quarkus.dynamodb.aws.credentials.static-provider.access-key-id", "local",
                "quarkus.dynamodb.aws.credentials.static-provider.secret-access-key", "local");
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
