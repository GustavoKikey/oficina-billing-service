package br.com.fiap.techchallenge.oficina.billing.external.pagamento;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssinaturaWebhookTest {

    private static final String SEGREDO = "segredo-de-teste";

    private static String assinar(String dataId, String requestId, String ts) {
        return "ts=" + ts + ",v1=" + AssinaturaWebhook.hmac(SEGREDO, AssinaturaWebhook.manifesto(dataId, requestId, ts));
    }

    @Test
    void manifestoNaOrdemDoMercadoPago() {
        assertEquals("id:abc123;request-id:req-1;ts:1700000000;",
                AssinaturaWebhook.manifesto("ABC123", "req-1", "1700000000"));
        assertEquals("ts:1700000000;", AssinaturaWebhook.manifesto(null, " ", "1700000000"));
    }

    @Test
    void aceitaAssinaturaCorreta() {
        assertTrue(AssinaturaWebhook.valida(SEGREDO, assinar("123", "req-1", "1700000000"), "req-1", "123"));
        assertTrue(AssinaturaWebhook.valida(SEGREDO, " ts=1700000000 , v1="
                + AssinaturaWebhook.hmac(SEGREDO, "id:123;request-id:req-1;ts:1700000000;").toUpperCase(),
                "req-1", "123"), "espaços e hex maiúsculo");
    }

    @Test
    void recusaQualquerDivergencia() {
        String assinatura = assinar("123", "req-1", "1700000000");
        assertFalse(AssinaturaWebhook.valida(SEGREDO, assinatura, "req-1", "999"), "outro pagamento");
        assertFalse(AssinaturaWebhook.valida(SEGREDO, assinatura, "req-2", "123"), "outra requisição");
        assertFalse(AssinaturaWebhook.valida("outro-segredo", assinatura, "req-1", "123"));
        assertFalse(AssinaturaWebhook.valida(SEGREDO, "ts=1700000000", "req-1", "123"), "sem v1");
        assertFalse(AssinaturaWebhook.valida(SEGREDO, "v1=abc", "req-1", "123"), "sem ts");
        assertFalse(AssinaturaWebhook.valida(SEGREDO, null, "req-1", "123"));
        assertFalse(AssinaturaWebhook.valida("", assinatura, "req-1", "123"), "sem segredo configurado: fecha");
        assertFalse(AssinaturaWebhook.valida(null, assinatura, "req-1", "123"));
    }

    @Test
    void ignoraPartesMalformadasOuDesconhecidasDoCabecalho() {
        String hmac = AssinaturaWebhook.hmac(SEGREDO, "id:123;request-id:req-1;ts:1700000000;");
        assertTrue(AssinaturaWebhook.valida(SEGREDO, "lixo,versao=2,ts=1700000000,v1=" + hmac, "req-1", "123"),
                "partes sem '=' e chaves desconhecidas não atrapalham");
        assertFalse(AssinaturaWebhook.valida(SEGREDO, "ts,v1", "req-1", "123"), "chaves sem valor");
    }
}
