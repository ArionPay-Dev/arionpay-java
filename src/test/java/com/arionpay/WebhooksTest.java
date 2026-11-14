package com.arionpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhooksTest {
    static final String SECRET = "whsec_unit_0001";
    static final String EVENT = "{\"id\":\"evt_abc\",\"type\":\"invoice.paid\",\"created\":1,\"livemode\":true,\"data\":{\"invoice\":"
            + "{\"id\":\"6af75d7ce83f10cb386f0981\",\"status\":\"paid\",\"orderId\":\"1001\",\"amountFiat\":49.9,\"currency\":\"USD\","
            + "\"asset\":\"USDT_TRC20\",\"paidCrypto\":\"49.900001\",\"test\":false,\"metadata\":{\"user\":42},\"transactions\":[]}}}";

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    static String signed(byte[] body, long t, String secret) {
        return "t=" + t + ",v1=" + Webhooks.hmacHex(secret, concat((t + ".").getBytes(StandardCharsets.UTF_8), body));
    }

    static String signed(String body) {
        return signed(body.getBytes(StandardCharsets.UTF_8), now(), SECRET);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    @Test
    void requestSignaturesMatchBackend() {
        // reference values computed independently with python3 hmac
        assertEquals("e6855fc36dc68ee45a227f7bfff6965585803c9530238b1b771746c118a91770",
                Webhooks.signRequest("sk_x", 1700000000, "POST", "/api/v1/invoices", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("76c4e503c0883422e6c050aef0becffba060d8814f3a8f95c604dc1914639ef2",
                Webhooks.signRequest("sk_x", 1700000000, "get", "/api/v1/invoices/abc?x=1", "ignored".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void hmacMatchesKnownVector() {
        // RFC 4231 test case 2
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Webhooks.hmacHex("Jefe", "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void headerParsing() {
        String v1 = "a".repeat(64);
        Webhooks.SignatureHeader h = Webhooks.parseSignatureHeader("t=123,v1=" + v1);
        assertEquals(123, h.timestamp);
        assertEquals(v1, h.v1);
        h = Webhooks.parseSignatureHeader(" v1=" + v1.toUpperCase() + " , t=5 ");
        assertEquals(5, h.timestamp);
        assertEquals(v1, h.v1);
        for (String bad : new String[] {"", null, "t=1", "v1=" + v1, "t=x,v1=" + v1, "t=1,v1=zz", "t=1,v1=" + v1 + "0", "t=-1,v1=" + v1}) {
            assertNull(Webhooks.parseSignatureHeader(bad), String.valueOf(bad));
        }
    }

    @Test
    void validEvent() {
        Event ev = Webhooks.verify(EVENT, signed(EVENT), SECRET);
        assertEquals("evt_abc", ev.getId());
        assertEquals("invoice.paid", ev.getType());
        assertTrue(ev.isLivemode());
        Invoice inv = ev.getInvoice();
        assertEquals("6af75d7ce83f10cb386f0981", inv.getId());
        assertTrue(inv.isPaid());
        assertEquals("1001", inv.getOrderId());
        assertEquals(new BigDecimal("49.9"), inv.getAmountFiat());
        assertEquals("49.900001", inv.getPaidCrypto());
        assertEquals(42L, inv.getMetadata().get("user"));
        assertTrue(inv.matches("49.90", "usd"));
        assertTrue(inv.matches(new BigDecimal("49.9"), "USD"));
        assertFalse(inv.matches("49.91", "USD"));
        assertFalse(inv.matches("49.90", "EUR"));
        assertFalse(inv.matches("abc", "USD"));
        assertFalse(Webhooks.isTestEvent(ev));
        byte[] bytes = EVENT.getBytes(StandardCharsets.UTF_8);
        assertTrue(Webhooks.isValidSignature(bytes, signed(EVENT), SECRET, 300, now()));
    }

    @Test
    void nonAscii() {
        String body = EVENT.replace("\"status\":\"paid\"", "\"status\":\"paid\",\"description\":\"Café ☕ – 東京 \\u00e9\"");
        Event ev = Webhooks.verify(body, signed(body), SECRET);
        assertEquals("Café ☕ – 東京 é", ev.getInvoice().getRaw().get("description"));
    }

    @Test
    void failures() {
        String hdr = signed(EVENT);
        assertThrows(SignatureException.class, () -> Webhooks.verify(EVENT.replace("paid", "PAID"), hdr, SECRET));
        assertThrows(SignatureException.class, () -> Webhooks.verify(" " + EVENT, hdr, SECRET));
        assertThrows(SignatureException.class, () -> Webhooks.verify(EVENT, hdr, "whsec_other"));
        assertTrue(assertThrows(SignatureException.class, () -> Webhooks.verify(EVENT, hdr, "")).getMessage().contains("empty"));
        assertTrue(assertThrows(SignatureException.class, () -> Webhooks.verify(EVENT, hdr, null)).getMessage().contains("empty"));
        byte[] body = EVENT.getBytes(StandardCharsets.UTF_8);
        long old = now() - 301;
        assertTrue(assertThrows(SignatureException.class, () -> Webhooks.verify(body, signed(body, old, SECRET), SECRET)).getMessage().contains("stale"));
        assertTrue(assertThrows(SignatureException.class, () -> Webhooks.verify(body, signed(body, old + 602, SECRET), SECRET)).getMessage().contains("stale"));
        assertTrue(Webhooks.isValidSignature(body, signed(body, old, SECRET), SECRET, 600, now()));
        assertTrue(assertThrows(SignatureException.class, () -> Webhooks.verify(body, null, SECRET)).getMessage().contains("Missing or malformed"));
        assertTrue(assertThrows(SignatureException.class, () -> Webhooks.verify((byte[]) null, hdr, SECRET)).getMessage().contains("raw request body"));
        assertFalse(Webhooks.isValidSignature(body, hdr, "", 300, now()));
        assertFalse(Webhooks.isValidSignature(null, hdr, SECRET, 300, now()));
    }

    @Test
    void signedButNotEvent() {
        String[] bodies = {"not json", "{}", "[]", "{\"id\":\"\",\"type\":\"invoice.paid\",\"data\":{\"invoice\":{}}}",
            "{\"id\":\"evt_1\",\"type\":\"invoice.paid\"}", "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"invoice\":null}}",
            "{\"id\":5,\"type\":\"invoice.paid\",\"data\":{\"invoice\":{}}}", "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"invoice\":{}}} x"};
        for (String b : bodies) {
            assertThrows(SignatureException.class, () -> Webhooks.verify(b, signed(b), SECRET), b);
        }
        byte[] notUtf8 = {(byte) 0xff, (byte) 0xfe, '{', '}'};
        assertThrows(SignatureException.class, () -> Webhooks.verify(notUtf8, signed(notUtf8, now(), SECRET), SECRET));
    }

    @Test
    void oddFieldTypesStillVerify() {
        String b = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"invoice\":{\"id\":\"6af75d7ce83f10cb386f0981\",\"status\":\"PAID\","
                + "\"amountFiat\":\"12.5\",\"metadata\":[1],\"transactions\":{\"x\":1},\"late\":\"yes\",\"paidAt\":null,\"orderId\":77,\"customerEmail\":\"a@b.c\"}}}";
        Invoice inv = Webhooks.verify(b, signed(b), SECRET).getInvoice();
        assertEquals("77", inv.getOrderId());
        assertEquals("paid", inv.getStatus());
        assertFalse(inv.matches("12.50", "USD"));
        assertTrue(inv.getMetadata().isEmpty());
        assertTrue(inv.getTransactions().isEmpty());
        assertFalse(inv.isLate());
        assertEquals("", inv.getPaidAt());
        assertEquals("a@b.c", inv.getRaw().get("customerEmail"));
    }

    @Test
    void testEvents() {
        String b1 = EVENT.replace("\"livemode\":true", "\"livemode\":false,\"test\":true");
        assertTrue(Webhooks.isTestEvent(Webhooks.verify(b1, signed(b1), SECRET)));
        String b2 = EVENT.replace("evt_abc", "evt_test_1");
        assertTrue(Webhooks.isTestEvent(Webhooks.verify(b2, signed(b2), SECRET)));
        String b3 = EVENT.replace("6af75d7ce83f10cb386f0981", "inv_test_x");
        assertTrue(Webhooks.isTestEvent(Webhooks.verify(b3, signed(b3), SECRET)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonReaderAndWriter() throws Exception {
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"a\":[1,-2,3.50,1e2,12345678901234567890,true,false,null,\"x\\n\\u00e9\\ud83d\\ude00\"],\"b\":{}}");
        List<Object> a = (List<Object>) m.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(-2L, a.get(1));
        assertEquals(new BigDecimal("3.50"), a.get(2));
        assertEquals(new BigDecimal("1e2"), a.get(3));
        assertEquals(new BigDecimal("12345678901234567890"), a.get(4));
        assertEquals(Boolean.TRUE, a.get(5));
        assertNull(a.get(7));
        assertEquals("x\né😀", a.get(8));
        for (String bad : new String[] {"", "{", "{\"a\"}", "[1,]", "01", "1.", "-", "\"\\x\"", "\"a", "{\"a\":1,}", "tru", "[\"\u0001\"]", "1 2"}) {
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        }
        assertThrows(Json.ParseException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
        assertEquals("{\"s\":\"q\\\"\\\\\\n\\u0001é\",\"n\":[1,2.5,10,3.50],\"t\":true,\"z\":null}",
                Json.write(new java.util.LinkedHashMap<String, Object>() {
                    private static final long serialVersionUID = 1L;
                    {
                        put("s", "q\"\\\n\u0001é");
                        put("n", List.of(1, 2.5, 10.0, new BigDecimal("3.50")));
                        put("t", true);
                        put("z", null);
                    }
                }));
        assertThrows(IllegalArgumentException.class, () -> Json.write(List.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(List.of(new Object())));
        assertNotNull(Json.parse(Json.write(Map.of("k", List.of("v")))));
    }
}
