package com.arionpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real ArionPay server. Skipped unless ARIONPAY_IT_BASE_URL is set:
 *
 * <pre>
 * ARIONPAY_IT_BASE_URL=http://127.0.0.1:18090 ARIONPAY_IT_API_KEY=pk_... ARIONPAY_IT_API_SECRET=sk_... \
 * ARIONPAY_IT_WEBHOOK_SECRET=whsec_... mvn test -Dtest=IntegrationTest
 * </pre>
 *
 * The store's webhook address must be http://127.0.0.1:&lt;ARIONPAY_IT_HOOK_PORT, default 18099&gt;/hook (a dev server
 * that allows private webhook addresses).
 */
class IntegrationTest {
    final List<Event> received = Collections.synchronizedList(new ArrayList<>());
    final List<String> refused = Collections.synchronizedList(new ArrayList<>());

    Event waitFor(String what, Predicate<Event> p) throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            synchronized (received) {
                for (Event e : received) {
                    if (p.test(e)) {
                        return e;
                    }
                }
            }
            Thread.sleep(300);
        }
        List<String> types = new ArrayList<>();
        synchronized (received) {
            received.forEach(e -> types.add(e.getType()));
        }
        fail(what + " not received; got " + types + ", refused " + refused);
        return null;
    }

    @Test
    void fullSandboxPaymentWithRealWebhook() throws Exception {
        String base = System.getenv("ARIONPAY_IT_BASE_URL");
        assumeTrue(base != null && !base.isEmpty(), "set ARIONPAY_IT_BASE_URL to run against a real server");
        int port = Integer.parseInt(System.getenv().getOrDefault("ARIONPAY_IT_HOOK_PORT", "18099"));
        String hook = "http://127.0.0.1:" + port + "/hook";
        ArionPay ap = ArionPay.builder().apiKey(System.getenv("ARIONPAY_IT_API_KEY")).apiSecret(System.getenv("ARIONPAY_IT_API_SECRET"))
                .webhookSecret(System.getenv("ARIONPAY_IT_WEBHOOK_SECRET")).baseUrl(base).appInfo("java-integration-test").build();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/hook", ex -> {
            byte[] raw = ex.getRequestBody().readAllBytes();
            int status;
            try {
                received.add(ap.verifyWebhook(raw, ex.getRequestHeaders().getFirst("ArionPay-Signature")));
                status = 200;
            } catch (SignatureException e) {
                refused.add(e.getMessage());
                status = 401;
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        try {
            assertTrue(ap.currencies().stream().anyMatch(c -> "USDT_TRC20".equals(c.get("symbol"))));
            assertFalse(ap.rates("EUR").isEmpty());

            String orderId = "java-it-" + System.currentTimeMillis();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("cart", "java");
            meta.put("n", 3);
            CreatedInvoice inv = ap.createInvoice(new InvoiceParams("7.25").currency("USD").orderId(orderId).asset("USDT_TRC20").test(true)
                    .metadata(meta).callbackUrl(hook));
            assertTrue(inv.getId().matches("[0-9a-f]{24}"), inv.getId());
            assertTrue(inv.getInvoiceUrl().endsWith("/pay/" + inv.getId()), inv.getInvoiceUrl());
            assertEquals(orderId, inv.getOrderId());

            String key = "java-key-" + System.currentTimeMillis();
            CreatedInvoice a = ap.createInvoice(new InvoiceParams("5").orderId(orderId + "-b").idempotencyKey(key).test(true));
            CreatedInvoice b = ap.createInvoice(new InvoiceParams("5").orderId(orderId + "-b").idempotencyKey(key).test(true));
            assertEquals(a.getId(), b.getId());
            ApiException reused = assertThrows(ApiException.class, () -> ap.createInvoice(new InvoiceParams("6").orderId(orderId + "-b").idempotencyKey(key).test(true)));
            assertEquals("idempotency_key_reused", reused.getCode());

            Invoice read = ap.getInvoice(inv.getId());
            assertEquals("new", read.getStatus());
            assertEquals("java", read.getMetadata().get("cart"));
            assertEquals(3L, read.getMetadata().get("n"));
            assertTrue(read.isTest());

            ap.simulatePayment(inv.getId(), "detected");
            Event pending = waitFor("invoice.pending", e -> e.getType().equals("invoice.pending") && e.getInvoice().getId().equals(inv.getId()));
            assertEquals("pending", pending.getInvoice().getStatus());
            ap.simulatePayment(inv.getId(), "confirm");
            Event paid = waitFor("invoice.paid", e -> e.getType().equals("invoice.paid") && e.getInvoice().getId().equals(inv.getId()));
            Invoice pi = paid.getInvoice();
            assertEquals(orderId, pi.getOrderId());
            assertTrue(pi.isTest());
            assertFalse(Webhooks.isTestEvent(paid));
            assertTrue(pi.matches("7.25", "USD"));
            assertEquals("USDT_TRC20", pi.getAsset());
            assertEquals("java", pi.getMetadata().get("cart"));

            read = ap.getInvoice(inv.getId());
            assertTrue(read.isPaid());
            assertTrue(read.matches("7.25", "USD"));
            assertNotEquals("0", read.getPaidCrypto());

            CreatedInvoice under = ap.createInvoice(new InvoiceParams("20").orderId(orderId + "-u").asset("USDT_TRC20").test(true).callbackUrl(hook));
            ap.simulatePayment(under.getId(), "underpaid_1pct");
            Event up = waitFor("tolerance invoice.paid", e -> e.getType().equals("invoice.paid") && e.getInvoice().getId().equals(under.getId()));
            assertTrue(up.getInvoice().isUnderpaidWithinTolerance());
            assertTrue(up.getInvoice().matches("20", "USD"));

            Map<String, Object> t = ap.sendTestWebhook("invoice.expired");
            assertEquals(Boolean.TRUE, t.get("delivered"), String.valueOf(t));
            assertTrue(Webhooks.isTestEvent(waitFor("test event", e -> e.getId().equals(t.get("eventId")))));

            assertEquals(404, assertThrows(ApiException.class, () -> ap.getInvoice("0".repeat(24))).getStatus());
            ArionPay wrong = ArionPay.builder().apiKey(System.getenv("ARIONPAY_IT_API_KEY")).apiSecret("sk_wrong").baseUrl(base).build();
            assertEquals(401, assertThrows(ApiException.class, () -> wrong.getInvoice(inv.getId())).getStatus());
            assertEquals(List.of(), refused);
            System.out.println("invoice " + inv.getId() + " paid, " + received.size() + " signed webhooks verified");
        } finally {
            server.stop(0);
        }
    }
}
