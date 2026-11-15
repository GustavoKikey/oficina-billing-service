package br.com.fiap.techchallenge.oficina.billing.external.api;

import br.com.fiap.techchallenge.oficina.billing.orcamento.controllers.BillingController;
import br.com.fiap.techchallenge.oficina.billing.orcamento.dtos.OrcamentoResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/** Consulta de orçamento e pagamento — itens, valor, cobrança e histórico do provedor. */
@Path("/orcamentos")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ATENDENTE", "ADMINISTRADOR"})
@Tag(name = "Orçamentos", description = "Orçamento e pagamento de cada OS")
public class OrcamentoResource {

    private final BillingController controller;

    public OrcamentoResource(BillingController controller) {
        this.controller = controller;
    }

    @GET
    @Path("/os/{osId}")
    @Operation(summary = "Orçamento da OS, com a cobrança no Mercado Pago e o histórico do pagamento")
    @APIResponse(responseCode = "404", description = "A OS ainda não tem orçamento")
    public OrcamentoResponse buscarPorOs(@PathParam("osId") UUID osId) {
        return controller.buscarPorOs(osId).orElseThrow(NotFoundException::new);
    }
}
