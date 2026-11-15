package br.com.fiap.techchallenge.oficina.billing.external.api.exception;

import br.com.fiap.techchallenge.oficina.billing.shared.entities.DomainException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * No Billing quase toda regra de negócio chega por mensagem, não por HTTP; o que vazar
 * para a API é entrada inválida (400), com o corpo padrão {@link ApiError}.
 */
@Provider
public class DomainExceptionMapper implements ExceptionMapper<DomainException> {

    @Override
    public Response toResponse(DomainException ex) {
        int status = Response.Status.BAD_REQUEST.getStatusCode();
        return Response.status(status).entity(ApiError.of(status, "Bad Request", ex.getMessage())).build();
    }
}
