package br.com.fiap.techchallenge.oficina.billing.orcamento.dtos;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrcamentoResponse(
        UUID id,
        UUID osId,
        String status,
        BigDecimal valorTotal,
        List<ItemView> itens,
        OffsetDateTime geradoEm,
        String aprovadoPor,
        OffsetDateTime aprovadoEm,
        String motivoCancelamento,
        PagamentoView pagamento
) {
    public record ItemView(String tipo, String referenciaId, String descricao, int quantidade,
                           BigDecimal valorUnitario, BigDecimal subtotal) {}

    public record PagamentoView(UUID id, String status, String link, BigDecimal valor, OffsetDateTime expiraEm,
                                String idPagamentoProvedor, String detalhe, OffsetDateTime confirmadoEm,
                                OffsetDateTime estornadoEm, List<String> historico) {}
}
