package com.arionpay;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Webhook and request signatures. */
public final class Webhooks {
    /** Webhooks signed more than this many seconds ago (or ahead) are refused. ArionPay signs again on every retry. */
    public static final long DEFAULT_TOLERANCE_SEC = 300;

    /** The webhook event types ArionPay sends. */
    public static final List<String> EVENT_TYPES = Collections.unmodifiableList(Arrays.asList(
            "invoice.pending", "invoice.partial", "invoice.paid", "invoice.expired", "invoice.needs_review"));

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,12}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    private Webhooks() {}

    static String hmacHex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(data);
            StringBuilder b = new StringBuilder(d.length * 2);
            for (byte x : d) {
                b.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] concat(String prefix, byte[] body) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] out = Arrays.copyOf(p, p.length + body.length);
        System.arraycopy(body, 0, out, p.length, body.length);
        return out;
    }

    /**
     * The x-signature of one API request. POST: hex HMAC-SHA256(secret, "&lt;ts&gt;.&lt;raw body&gt;"),
     * GET: hex HMAC-SHA256(secret, "&lt;ts&gt;.GET &lt;path and query&gt;").
     */
    public static String signRequest(String secretKey, long timestamp, String method, String pathAndQuery, byte[] rawBody) {
        if ("GET".equalsIgnoreCase(method)) {
            return hmacHex(secretKey, (timestamp + ".GET " + pathAndQuery).getBytes(StandardCharsets.UTF_8));
        }
        return hmacHex(secretKey, concat(timestamp + ".", rawBody == null ? new byte[0] : rawBody));
    }

    /** Reads "t=&lt;unix seconds&gt;,v1=&lt;hex&gt;". Returns null when the header is malformed. */
    public static SignatureHeader parseSignatureHeader(String header) {
        if (header == null) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                values.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        String t = values.get("t");
        String v1 = values.get("v1");
        if (t == null || v1 == null || !DIGITS.matcher(t).matches() || !HEX64.matcher(v1).matches()) {
            return null;
        }
        return new SignatureHeader(Long.parseLong(t), v1.toLowerCase(Locale.ROOT));
    }

    /** A parsed ArionPay-Signature header. */
    public static final class SignatureHeader {
        public final long timestamp;
        public final String v1;

        SignatureHeader(long timestamp, String v1) {
            this.timestamp = timestamp;
            this.v1 = v1;
        }
    }

    /** True when header is a valid, fresh signature of exactly rawBody. Never throws. */
    public static boolean isValidSignature(byte[] rawBody, String header, String webhookSecret, long toleranceSec, long nowUnix) {
        if (rawBody == null || webhookSecret == null || webhookSecret.isEmpty()) {
            return false;
        }
        SignatureHeader h = parseSignatureHeader(header);
        if (h == null || Math.abs(nowUnix - h.timestamp) > toleranceSec) {
            return false;
        }
        String expected = hmacHex(webhookSecret, concat(h.timestamp + ".", rawBody));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), h.v1.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Checks a webhook and returns the event, or throws {@link SignatureException} (answer 401).
     *
     * @param rawBody the exact bytes ArionPay sent (Spring: {@code @RequestBody byte[]}), never parsed and re-encoded JSON
     * @param header the ArionPay-Signature header
     * @param webhookSecret your webhook signing secret (whsec_...)
     */
    public static Event verify(byte[] rawBody, String header, String webhookSecret) {
        return verify(rawBody, header, webhookSecret, DEFAULT_TOLERANCE_SEC, System.currentTimeMillis() / 1000);
    }

    /** {@link #verify(byte[], String, String)} for a body you already hold as text (it is signed as UTF-8). */
    public static Event verify(String rawBody, String header, String webhookSecret) {
        return verify(rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8), header, webhookSecret);
    }

    /** {@link #verify(byte[], String, String)} with your own clock window. */
    @SuppressWarnings("unchecked")
    public static Event verify(byte[] rawBody, String header, String webhookSecret, long toleranceSec, long nowUnix) {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            throw new SignatureException("The webhook secret is empty. Copy it from the Webhooks page of your ArionPay dashboard (it starts with whsec_).");
        }
        if (rawBody == null) {
            throw new SignatureException("Pass the raw request body (the exact bytes ArionPay sent).");
        }
        if (parseSignatureHeader(header) == null) {
            throw new SignatureException("Missing or malformed ArionPay-Signature header.");
        }
        if (!isValidSignature(rawBody, header, webhookSecret, toleranceSec, nowUnix)) {
            throw new SignatureException("Invalid webhook signature or stale timestamp. Check the webhook secret and your server clock.");
        }
        Object parsed;
        try {
            parsed = Json.parse(rawBody);
        } catch (Json.ParseException e) {
            throw new SignatureException("The webhook body is not JSON.");
        }
        if (!(parsed instanceof Map)) {
            throw new SignatureException("The webhook body is not an ArionPay event.");
        }
        Map<String, Object> ev = (Map<String, Object>) parsed;
        Object data = ev.get("data");
        Object invoice = data instanceof Map ? ((Map<String, Object>) data).get("invoice") : null;
        if (!(ev.get("id") instanceof String) || ((String) ev.get("id")).isEmpty()
                || !(ev.get("type") instanceof String) || ((String) ev.get("type")).isEmpty()
                || !(invoice instanceof Map)) {
            throw new SignatureException("The webhook body is not an ArionPay event.");
        }
        return new Event(ev, Invoice.fromWebhook((Map<String, Object>) invoice));
    }

    /** True for events made by "Send test webhook": a made-up invoice that must never change an order. */
    public static boolean isTestEvent(Event event) {
        return event.isTest() || event.getId().startsWith("evt_test_") || event.getInvoice().getId().startsWith("inv_test_");
    }
}
