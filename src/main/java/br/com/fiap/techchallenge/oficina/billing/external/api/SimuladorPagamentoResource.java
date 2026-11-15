package br.com.fiap.techchallenge.oficina.billing.external.api;

import br.com.fiap.techchallenge.oficina.billing.external.pagamento.ProvedorSimulado;
import br.com.fiap.techchallenge.oficina.billing.orcamento.gateways.ProvedorPagamentoGateway;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

/**
 * Checkout simulado — só existe com {@code PAGAMENTO_PROVEDOR=simulado}; com o Mercado Pago
 * real, todas as rotas respondem 404. Faz o papel da tela de pagamento do provedor.
 */
@Path("/simulador")
@PermitAll
@Tag(name = "Simulador de pagamento", description = "Só no modo simulado (sem credenciais do Mercado Pago)")
public class SimuladorPagamentoResource {

    private final ProvedorPagamentoGateway provedor;

    public SimuladorPagamentoResource(ProvedorPagamentoGateway provedor) {
        this.provedor = provedor;
    }

    @GET
    @Path("/checkout/{orcamentoId}")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(summary = "Página do link de pagamento simulado")
    public String checkout(@PathParam("orcamentoId") String orcamentoId) {
        simulador(orcamentoId);
        return """
                Checkout simulado — orçamento %s

                Para pagar:   POST /simulador/pagamentos/%s?resultado=aprovado
                Para recusar: POST /simulador/pagamentos/%s?resultado=recusado

                A confirmação chega ao Billing pela mesma consulta periódica usada com o Mercado Pago.
                """.formatted(orcamentoId, orcamentoId, orcamentoId);
    }

    @POST
    @Path("/pagamentos/{orcamentoId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "O cliente paga (aprovado) ou o cartão é recusado")
    public Map<String, String> pagar(@PathParam("orcamentoId") String orcamentoId,
                                     @QueryParam("resultado") String resultado) {
        var situacao = simulador(orcamentoId).pagar(orcamentoId, !"recusado".equalsIgnoreCase(resultado));
        return Map.of("idPagamento", situacao.idPagamento(), "situacao", situacao.situacao().name());
    }

    private ProvedorSimulado simulador(String orcamentoId) {
        if (provedor instanceof ProvedorSimulado s && s.conhece(orcamentoId)) {
            return s;
        }
        throw new NotFoundException();
    }
}
