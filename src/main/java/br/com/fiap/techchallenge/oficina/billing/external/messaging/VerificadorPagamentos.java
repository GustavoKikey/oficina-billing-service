package br.com.fiap.techchallenge.oficina.billing.external.messaging;

import br.com.fiap.techchallenge.oficina.billing.orcamento.controllers.BillingController;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Consulta periódica dos pagamentos pendentes (RFC 004 §3.2): a garantia de que a
 * confirmação chega mesmo sem webhook — ambiente local, load balancer fechado, webhook
 * perdido. Também expira os links vencidos.
 */
@ApplicationScoped
public class VerificadorPagamentos {

    private static final Logger LOG = Logger.getLogger(VerificadorPagamentos.class);

    private final BillingController controller;

    public VerificadorPagamentos(BillingController controller) {
        this.controller = controller;
    }

    @Scheduled(every = "${oficina.pagamento.verificacao:15s}", delayed = "${oficina.pagamento.atraso-inicial:10s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void verificar() {
        try {
            int mudaram = controller.verificarPagamentosPendentes();
            if (mudaram > 0) {
                LOG.infof("Consulta de pagamentos: %d orçamento(s) atualizado(s)", mudaram);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Consulta de pagamentos falhou — nova tentativa no próximo ciclo");
        }
    }
}
