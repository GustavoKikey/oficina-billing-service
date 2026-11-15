package br.com.fiap.techchallenge.oficina.billing.external.pagamento;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Validação da assinatura dos webhooks do Mercado Pago (RFC 004 §3.2).
 *
 * <p>Cabeçalho {@code x-signature: ts=<epoch>,v1=<hmac>}; o HMAC-SHA256 (hex), com a
 * chave secreta da aplicação, é calculado sobre o manifesto
 * {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;} — partes ausentes são omitidas,
 * e o {@code data.id} alfanumérico vai em minúsculas.
 *
 * <p>Mesmo com a assinatura válida, o corpo do webhook nunca decide nada: ele só dispara
 * a consulta do pagamento na API (defesa contra replay e conteúdo forjado).
 */
public final class AssinaturaWebhook {

    private AssinaturaWebhook() {}

    public static boolean valida(String segredo, String xSignature, String xRequestId, String dataId) {
        if (segredo == null || segredo.isBlank() || xSignature == null) {
            return false;
        }
        String ts = null;
        String v1 = null;
        for (String parte : xSignature.split(",")) {
            String[] kv = parte.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals("ts")) {
                ts = kv[1].trim();
            } else if (kv.length == 2 && kv[0].trim().equals("v1")) {
                v1 = kv[1].trim();
            }
        }
        if (ts == null || v1 == null) {
            return false;
        }
        String esperado = hmac(segredo, manifesto(dataId, xRequestId, ts));
        return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    static String manifesto(String dataId, String xRequestId, String ts) {
        StringBuilder m = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            m.append("id:").append(dataId.toLowerCase(Locale.ROOT)).append(';');
        }
        if (xRequestId != null && !xRequestId.isBlank()) {
            m.append("request-id:").append(xRequestId).append(';');
        }
        m.append("ts:").append(ts).append(';');
        return m.toString();
    }

    static String hmac(String segredo, String mensagem) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(mensagem.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponível", e);
        }
    }
}
