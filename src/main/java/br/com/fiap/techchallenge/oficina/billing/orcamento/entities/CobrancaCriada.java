package br.com.fiap.techchallenge.oficina.billing.orcamento.entities;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Cobrança criada no provedor (no Mercado Pago, uma preferência do Checkout Pro).
 *
 * @param referencia id da cobrança no provedor (id da preferência)
 * @param link       onde o cliente paga ({@code init_point}/{@code sandbox_init_point})
 */
public record CobrancaCriada(String referencia, String link, OffsetDateTime expiraEm) {

    public CobrancaCriada {
        Objects.requireNonNull(link, "link");
    }
}
