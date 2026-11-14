package com.arionpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientTest {
    static final class Call {
        final String method;
        final String url;
        final Map<String, String> headers;
        final byte[] body;

        Call(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = new HashMap<>(headers);
            this.body = body;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final class Answer {
        final int status;
        final String body;
        final Map<String, String> headers;

        Answer(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    /** Answers each request with the next answer: an IOException is thrown, an Answer answered. */
    static final class Fake implements ArionPay.Transport {
        final LinkedList<Object> answers;
        final List<Call> calls = new ArrayList<>();

        Fake(Object... answers) {
            this.answers = new LinkedList<>(Arrays.asList(answers));
        }

        @Override
        public ArionPay.Response send(String method, String url, Map<String, String> headers, byte[] body, java.time.Duration t) throws IOException {
            calls.add(new Call(method, url, headers, body));
            Object a = answers.removeFirst();
            if (a instanceof IOException) {
                throw (IOException) a;
            }
            Answer r = (Answer) a;
            return new ArionPay.Response(r.status, r.headers, r.body.getBytes(StandardCharsets.UTF_8));
        }
    }

    static Answer answer(int status, String json) {
        return new Answer(status, json, new HashMap<>());
    }

    final List<Long> waits = new ArrayList<>();

    ArionPay client(Fake fake, String baseUrl, int maxAttempts) {
        ArionPay c = ArionPay.builder().apiKey("pk_unit_000001").apiSecret("sk_unit").baseUrl(baseUrl).maxAttempts(maxAttempts).build();
        c.transport = fake;
        c.sleeper = waits::add;
        long[] ts = {1700000000L};
        c.clock = () -> ++ts[0];
        return c;
    }

    ArionPay client(Fake fake) {
        return client(fake, "https://example.test/", 3);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(byte[] b) throws Exception {
        return (Map<String, Object>) Json.parse(b);
    }

    @Test
    void constructorChecks() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArionPay.builder().apiKey("nope").apiSecret("s").build()).getMessage().contains("pk_"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArionPay.builder().apiKey("pk_abcdefgh").build()).getMessage().contains("apiSecret"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArionPay.builder().apiKey("pk_abcdefgh").apiSecret("s").baseUrl("ftp://x").build()).getMessage().contains("http"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArionPay.builder().apiKey("pk_abcdefgh").apiSecret("s").baseUrl("not a url").build()).getMessage().contains("http"));
        ArionPay c = ArionPay.builder().apiKey("pk_abcdefgh").apiSecret("sk_hidden").appInfo("myshop/2.1").build();
        assertTrue(c.getUserAgent().matches("arionpay-java/" + ArionPay.VERSION + " java/\\S+ myshop/2\\.1"), c.getUserAgent());
        assertFalse(c.toString().contains("sk_hidden"));
        assertThrows(IllegalArgumentException.class, () -> new InvoiceParams(" "));
        assertThrows(IllegalArgumentException.class, () -> new InvoiceParams((String) null));
    }

    @Test
    void createSignsExactBody() throws Exception {
        String a24 = "a".repeat(24);
        Fake f = new Fake(answer(201, "{\"status\":\"success\",\"id\":\"" + a24 + "\",\"invoice_url\":\"https://x/pay/1\",\"data\":{\"id\":\"" + a24
                + "\",\"invoice_url\":\"https://x/pay/1\",\"amount_fiat\":9.5,\"currency\":\"EUR\",\"status\":\"new\",\"order_id\":\"7\",\"amount_crypto\":\"10.123456789012345678\"}}"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "Zoë <b>");
        meta.put("n", 3);
        CreatedInvoice inv = client(f).createInvoice(new InvoiceParams("9.50").currency("EUR").orderId("7").metadata(meta).test(true));
        assertEquals("https://x/pay/1", inv.getInvoiceUrl());
        assertEquals(new BigDecimal("9.5"), inv.getAmountFiat());
        assertEquals("10.123456789012345678", inv.getAmountCrypto());
        assertEquals("7", inv.getOrderId());
        Call c = f.calls.get(0);
        assertEquals("POST", c.method);
        assertEquals("https://example.test/api/v1/invoices", c.url);
        Map<String, Object> body = json(c.body);
        assertEquals("9.50", body.get("amount"));
        assertEquals("EUR", body.get("currency"));
        assertEquals(Boolean.TRUE, body.get("test"));
        assertEquals("Zoë <b>", ((Map<?, ?>) body.get("metadata")).get("name"));
        assertFalse(body.containsKey("description"));
        assertTrue(((String) body.get("idempotencyKey")).startsWith("sdk_"));
        assertEquals(Webhooks.hmacHex("sk_unit", WebhooksTest.concat((c.headers.get("x-timestamp") + ".").getBytes(StandardCharsets.UTF_8), c.body)), c.headers.get("x-signature"));
        assertEquals("pk_unit_000001", c.headers.get("x-api-key"));
        assertEquals("application/json", c.headers.get("Content-Type"));
    }

    @Test
    void getSignedOverPathWithBasePath() {
        String b24 = "b".repeat(24);
        Fake f = new Fake(answer(200, "{\"invoiceId\":\"" + b24 + "\",\"status\":\"PAID\",\"amountFiat\":1,\"currency\":\"USD\",\"test\":true,"
                + "\"settledCurrency\":\"USDT_TRC20\",\"amountReceived\":\"1.000000\",\"settledAt\":\"2026-11-14T00:00:00Z\",\"underpaidWithinTolerance\":true,\"metadata\":{\"k\":\"v\"},\"confirmations\":3}"));
        Invoice inv = client(f, "https://example.test/gw/", 3).getInvoice(b24);
        assertEquals(b24, inv.getId());
        assertTrue(inv.isPaid());
        assertTrue(inv.isTest());
        assertEquals("USDT_TRC20", inv.getAsset());
        assertEquals("1.000000", inv.getPaidCrypto());
        assertEquals("0", inv.getConfirmedCrypto());
        assertEquals("2026-11-14T00:00:00Z", inv.getPaidAt());
        assertTrue(inv.isUnderpaidWithinTolerance());
        assertEquals("v", inv.getMetadata().get("k"));
        assertEquals(3L, inv.getRaw().get("confirmations"));
        assertTrue(inv.matches("1.00", "USD"));
        Call c = f.calls.get(0);
        assertEquals("https://example.test/gw/api/v1/invoices/" + b24, c.url);
        assertEquals(Webhooks.hmacHex("sk_unit", (c.headers.get("x-timestamp") + ".GET /gw/api/v1/invoices/" + b24).getBytes(StandardCharsets.UTF_8)), c.headers.get("x-signature"));
        assertNull(c.body);
    }

    @Test
    void replayResignedOnce() {
        String c24 = "c".repeat(24);
        Answer used = answer(401, "{\"error\":\"This signed request was already used. Sign each request with a fresh timestamp.\"}");
        Fake f = new Fake(used, answer(200, "{\"invoiceId\":\"" + c24 + "\",\"status\":\"NEW\"}"));
        assertEquals("new", client(f).getInvoice(c24).getStatus());
        assertEquals(2, f.calls.size());
        assertNotEquals(f.calls.get(0).headers.get("x-timestamp"), f.calls.get(1).headers.get("x-timestamp"));
        assertEquals(List.of(1100L), waits);
        Fake f2 = new Fake(used, used);
        assertEquals(401, assertThrows(ApiException.class, () -> client(f2).getInvoice(c24)).getStatus());
        assertEquals(2, f2.calls.size());
    }

    @Test
    void createRetryKeepsIdempotencyKey() {
        Fake f = new Fake(new ConnectException("Connection refused"), answer(503, "{\"error\":\"busy\"}"),
                answer(201, "{\"id\":\"" + "d".repeat(24) + "\",\"invoice_url\":\"u\",\"data\":{}}"));
        CreatedInvoice inv = client(f).createInvoice(new InvoiceParams(new BigDecimal("1")));
        assertEquals("new", inv.getStatus());
        assertEquals("USD", inv.getCurrency());
        assertEquals(3, f.calls.size());
        assertEquals(f.calls.get(0).bodyText(), f.calls.get(2).bodyText());
        assertEquals(List.of(500L, 1500L), waits);
    }

    @Test
    void rateLimit() {
        String e24 = "e".repeat(24);
        Map<String, String> h = new HashMap<>();
        h.put("retry-after", "2");
        Fake f = new Fake(new Answer(429, "{\"error\":\"slow down\",\"code\":\"rate_limited\"}", h), answer(200, "{\"invoiceId\":\"" + e24 + "\",\"status\":\"NEW\"}"));
        client(f).getInvoice(e24);
        assertEquals(List.of(2000L), waits);
        Fake f2 = new Fake(answer(429, "{\"error\":\"slow down\",\"code\":\"rate_limited\",\"retryAfterSec\":60}"));
        ApiException e = assertThrows(ApiException.class, () -> client(f2).getInvoice(e24));
        assertEquals(429, e.getStatus());
        assertEquals("rate_limited", e.getCode());
        assertEquals(1, f2.calls.size());
    }

    @Test
    void noRetryForSimulateAndCodes() {
        Fake f = new Fake(answer(503, "{\"error\":\"busy\"}"));
        assertEquals(503, assertThrows(ApiException.class, () -> client(f).simulatePayment("e".repeat(24), "exact")).getStatus());
        assertEquals(1, f.calls.size());
        Fake f2 = new Fake(answer(409, "{\"error\":\"This Idempotency-Key was already used\",\"code\":\"idempotency_key_reused\"}"));
        ApiException e = assertThrows(ApiException.class, () -> client(f2).createInvoice(new InvoiceParams("1").idempotencyKey("k")));
        assertEquals(409, e.getStatus());
        assertEquals("idempotency_key_reused", e.getCode());
        assertEquals(1, f2.calls.size());
        assertTrue(e.getMessage().contains("(409)"));
        assertFalse(e.getMessage().contains("sk_unit"));
    }

    @Test
    void unreachable() {
        Fake f = new Fake(new ConnectException("down"), new java.net.http.HttpTimeoutException("timed out"));
        ApiException e = assertThrows(ApiException.class, () -> client(f, "https://example.test", 2).getInvoice("f".repeat(24)));
        assertEquals(0, e.getStatus());
        assertEquals("network_error", e.getCode());
        assertTrue(e.getMessage().contains("Could not reach"));
        assertTrue(e.getCause() instanceof java.net.http.HttpTimeoutException);
        assertEquals(2, f.calls.size());
    }

    @Test
    void nonJsonErrorPage() {
        Fake f = new Fake(answer(502, "<html>Bad gateway</html>"), answer(502, "<html>"), answer(502, ""));
        ApiException e = assertThrows(ApiException.class, () -> client(f).getInvoice("f".repeat(24)));
        assertEquals(502, e.getStatus());
        assertEquals("ArionPay API error (502): HTTP 502", e.getMessage());
    }

    @Test
    void badInputNeverReachesNetwork() {
        Fake f = new Fake();
        ArionPay c = client(f);
        assertThrows(IllegalArgumentException.class, () -> c.getInvoice("../admin"));
        assertThrows(IllegalArgumentException.class, () -> c.getInvoice(null));
        assertThrows(IllegalArgumentException.class, () -> c.simulatePayment("a".repeat(24), "free_money"));
        assertThrows(IllegalArgumentException.class, () -> c.createInvoice(null));
        assertTrue(f.calls.isEmpty());
    }

    @Test
    void readsAndTestWebhook() throws Exception {
        Fake f = new Fake(answer(200, "{\"currencies\":[{\"symbol\":\"USDT_TRC20\",\"usdRate\":1},\"junk\"]}"), answer(200, "{\"currency\":\"EUR\"}"),
                answer(200, "{\"delivered\":true,\"eventId\":\"evt_test_1\"}"));
        ArionPay c = client(f);
        List<Map<String, Object>> cur = c.currencies();
        assertEquals(1, cur.size());
        assertEquals("USDT_TRC20", cur.get(0).get("symbol"));
        assertNull(f.calls.get(0).headers.get("x-signature"));
        assertEquals("EUR", c.rates("EUR").get("currency"));
        assertTrue(f.calls.get(1).url.endsWith("/api/v1/rates?currency=EUR"));
        assertEquals(Boolean.TRUE, c.sendTestWebhook(null).get("delivered"));
        assertEquals("invoice.paid", json(f.calls.get(2).body).get("eventType"));
    }

    @Test
    void clientVerifyWebhook() {
        ArionPay c = ArionPay.builder().apiKey("pk_abcdefgh").apiSecret("s").webhookSecret(WebhooksTest.SECRET).build();
        assertEquals("invoice.paid", c.verifyWebhook(WebhooksTest.EVENT, WebhooksTest.signed(WebhooksTest.EVENT)).getType());
    }

    /** The real java.net.http transport against the JDK's own HTTP server. */
    @Test
    void realHttpTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Call> seen = new ArrayList<>();
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
            synchronized (seen) {
                seen.add(new Call(ex.getRequestMethod(), ex.getRequestURI().toString(), h, body));
            }
            byte[] out;
            int status;
            if (ex.getRequestURI().getPath().contains("/redirect/")) {
                ex.getResponseHeaders().add("Location", "https://evil.test/");
                status = 302;
                out = new byte[0];
            } else if (ex.getRequestMethod().equals("POST")) {
                status = 201;
                out = ("{\"id\":\"" + "a".repeat(24) + "\",\"invoice_url\":\"https://pay.test/pay/x\",\"data\":{}}").getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                out = ("{\"invoiceId\":\"" + "b".repeat(24) + "\",\"status\":\"NEW\"}").getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gw";
            ArionPay c = ArionPay.builder().apiKey("pk_unit_000001").apiSecret("sk_unit").baseUrl(base).appInfo("t/1").build();
            assertEquals("https://pay.test/pay/x", c.createInvoice(new InvoiceParams("2.00").orderId("é-1")).getInvoiceUrl());
            assertEquals("new", c.getInvoice("b".repeat(24)).getStatus());
            Call post = seen.get(0);
            assertEquals("/gw/api/v1/invoices", post.url);
            assertEquals("application/json", post.headers.get("content-type"));
            assertTrue(post.headers.get("user-agent").startsWith("arionpay-java/"));
            assertEquals(Webhooks.hmacHex("sk_unit", WebhooksTest.concat((post.headers.get("x-timestamp") + ".").getBytes(StandardCharsets.UTF_8), post.body)), post.headers.get("x-signature"));
            assertEquals("é-1", json(post.body).get("orderId"));
            Call get = seen.get(1);
            assertEquals("GET", get.method);
            assertEquals(0, get.body.length);
            assertEquals(Webhooks.hmacHex("sk_unit", (get.headers.get("x-timestamp") + ".GET /gw/api/v1/invoices/" + "b".repeat(24)).getBytes(StandardCharsets.UTF_8)), get.headers.get("x-signature"));
            // redirects are never followed (the signature must not travel to another host)
            ArionPay r = ArionPay.builder().apiKey("pk_unit_000001").apiSecret("sk_unit").baseUrl(base + "/redirect").build();
            ApiException e = assertThrows(ApiException.class, () -> r.getInvoice("f".repeat(24)));
            assertEquals(302, e.getStatus());
            // nothing listening: status 0 after the retries
            ArionPay down = ArionPay.builder().apiKey("pk_unit_000001").apiSecret("sk_unit").baseUrl("http://127.0.0.1:1").maxAttempts(1).build();
            assertEquals(0, assertThrows(ApiException.class, () -> down.getInvoice("f".repeat(24))).getStatus());
        } finally {
            server.stop(0);
        }
    }
}
