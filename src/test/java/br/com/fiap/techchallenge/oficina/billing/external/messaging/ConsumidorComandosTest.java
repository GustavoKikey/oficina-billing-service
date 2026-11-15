package br.com.fiap.techchallenge.oficina.billing.external.messaging;

import br.com.fiap.techchallenge.oficina.billing.orcamento.controllers.BillingController;
import br.com.fiap.techchallenge.oficina.billing.orcamento.entities.EventoBilling;
import br.com.fiap.techchallenge.oficina.billing.orcamento.usecases.ComandosBillingUseCase;
import br.com.fiap.techchallenge.oficina.billing.shared.gateways.MetricasGateway;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumidorComandosTest {

    private static final EventoBilling RESPOSTA = new EventoBilling("Resposta", Map.of());

    private BillingController billing;
    private MetricasGateway metricas;
    private ConsumidorComandos consumidor;
    private final AtomicBoolean confirmada = new AtomicBoolean();
    private final AtomicReference<Throwable> rejeitada = new AtomicReference<>();

    @BeforeEach
    void preparar() {
        billing = mock(BillingController.class);
        metricas = mock(MetricasGateway.class);
        consumidor = new ConsumidorComandos(billing, metricas, 3, 0);
    }

    private Message<Object> mensagem(Object corpo) {
        return Message.of(corpo,
                () -> {
                    confirmada.set(true);
                    return CompletableFuture.completedFuture(null);
                },
                erro -> {
                    rejeitada.set(erro);
                    return CompletableFuture.completedFuture(null);
                });
    }

    private static JsonObject comando(String tipo, JsonObject payload) {
        return EnvelopeTest.envelopeValido().put("type", tipo).put("payload", payload);
    }

    @Test
    void gerarOrcamentoComClienteEItens() {
        when(billing.gerarOrcamento(any())).thenReturn(RESPOSTA);

        consumidor.receber(mensagem(comando("GerarOrcamento", new JsonObject()
                .put("cliente", new JsonObject().put("id", "c1").put("nome", "Alice").put("email", "a@x.com"))
                .put("itens", new JsonArray().add(new JsonObject().put("tipo", "PECA").put("referenciaId", "p1")
                        .put("descricao", "Pastilha").put("quantidade", 2).put("valorUnitario", "95.00"))))))
                .toCompletableFuture().join();

        ArgumentCaptor<ComandosBillingUseCase.GerarOrcamento> c = ArgumentCaptor.forClass(ComandosBillingUseCase.GerarOrcamento.class);
        verify(billing).gerarOrcamento(c.capture());
        assertEquals("a@x.com", c.getValue().cliente().email());
        assertEquals("95.00", c.getValue().itens().get(0).valorUnitario());
        assertTrue(confirmada.get());
    }

    @Test
    void demaisComandos() {
        when(billing.solicitarPagamento(any())).thenReturn(null); // saga já seguiu adiante: sem resposta
        when(billing.cancelarOrcamento(any())).thenReturn(RESPOSTA);
        when(billing.estornarPagamento(any())).thenReturn(RESPOSTA);

        consumidor.receber(mensagem(comando("SolicitarPagamento", new JsonObject()
                .put("aprovadoPor", "CLIENTE").put("aprovadoEm", "2026-11-15T15:00:00Z")))).toCompletableFuture().join();
        consumidor.receber(mensagem(comando("CancelarOrcamento", new JsonObject().put("motivo", "X"))))
                .toCompletableFuture().join();
        consumidor.receber(mensagem(comando("EstornarPagamento", new JsonObject().put("motivo", "Y"))))
                .toCompletableFuture().join();

        ArgumentCaptor<ComandosBillingUseCase.SolicitarPagamento> s = ArgumentCaptor.forClass(ComandosBillingUseCase.SolicitarPagamento.class);
        verify(billing).solicitarPagamento(s.capture());
        assertEquals("CLIENTE", s.getValue().aprovadoPor());
        verify(billing).cancelarOrcamento(any());
        verify(billing).estornarPagamento(any());
        assertTrue(confirmada.get());
    }

    @Test
    void comandoDeOutraFilaEIgnorado() {
        consumidor.receber(mensagem(comando("AbrirOrdemExecucao", new JsonObject()))).toCompletableFuture().join();
        assertTrue(confirmada.get());
        verify(billing, never()).gerarOrcamento(any());
    }

    @Test
    void formaInvalidaVaiParaADlq() {
        consumidor.receber(mensagem(comando("GerarOrcamento", new JsonObject()))).toCompletableFuture().join();
        assertInstanceOf(EnvelopeInvalidoException.class, rejeitada.get());

        rejeitada.set(null);
        consumidor.receber(mensagem(comando("SolicitarPagamento", new JsonObject().put("aprovadoEm", "ontem"))))
                .toCompletableFuture().join();
        assertInstanceOf(EnvelopeInvalidoException.class, rejeitada.get());
        verify(metricas, times(2)).falhaIntegracao("rabbitmq-comando-invalido");
    }

    @Test
    void falhaTecnicaPersistenteVaiParaADlq() {
        when(billing.cancelarOrcamento(any())).thenThrow(new IllegalStateException("DynamoDB fora"));

        consumidor.receber(mensagem(comando("CancelarOrcamento", new JsonObject()))).toCompletableFuture().join();

        verify(billing, times(3)).cancelarOrcamento(any());
        assertFalse(confirmada.get());
        verify(metricas).falhaProcessamento("comando-CancelarOrcamento");
    }
}
