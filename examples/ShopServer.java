// A minimal shop with the JDK's own HTTP server: /pay/<order> sends the customer to ArionPay,
// /arionpay/webhook marks the order paid.
//
//   ARIONPAY_API_KEY=pk_... ARIONPAY_API_SECRET=sk_... ARIONPAY_WEBHOOK_SECRET=whsec_... \
//   PUBLIC_URL=https://shop.example.com java -cp arionpay-java-1.0.0.jar examples/ShopServer.java

import com.arionpay.ApiException;
import com.arionpay.ArionPay;
import com.arionpay.CreatedInvoice;
import com.arionpay.Event;
import com.arionpay.Invoice;
import com.arionpay.InvoiceParams;
import com.arionpay.SignatureException;
import com.arionpay.Webhooks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ShopServer {
    record Order(String total, String currency) {}

    static final Map<String, Order> ORDERS = Map.of("3001", new Order("49.90", "USD")); // use your database
    static final Set<String> PAID = ConcurrentHashMap.newKeySet();
    static final Set<String> PROCESSED = ConcurrentHashMap.newKeySet(); // event ids already handled

    public static void main(String[] args) throws IOException {
        ArionPay arionpay = ArionPay.builder()
                .apiKey(System.getenv("ARIONPAY_API_KEY"))
                .apiSecret(System.getenv("ARIONPAY_API_SECRET"))
                .webhookSecret(System.getenv("ARIONPAY_WEBHOOK_SECRET"))
                .baseUrl(System.getenv().getOrDefault("ARIONPAY_BASE_URL", ArionPay.DEFAULT_BASE_URL))
                .appInfo("example-shop/1.0")
                .build();
        String publicUrl = System.getenv("PUBLIC_URL").replaceAll("/+$", "");
        boolean testMode = !"1".equals(System.getenv("ARIONPAY_LIVE"));
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/pay/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/pay/".length());
            Order order = ORDERS.get(id);
            if (order == null) {
                reply(ex, 404, "No such order");
                return;
            }
            try {
                CreatedInvoice inv = arionpay.createInvoice(new InvoiceParams(order.total())
                        .currency(order.currency())
                        .orderId(id)
                        .successUrl(publicUrl + "/thanks/" + id)
                        .cancelUrl(publicUrl + "/cart")
                        .callbackUrl(publicUrl + "/arionpay/webhook")
                        .idempotencyKey("order-" + id + "-" + order.total() + order.currency()) // one invoice per order total
                        .test(testMode));
                ex.getResponseHeaders().add("Location", inv.getInvoiceUrl());
                ex.sendResponseHeaders(303, -1);
                ex.close();
            } catch (ApiException e) {
                System.err.println("ArionPay: " + e.getMessage());
                reply(ex, 502, "The crypto payment could not be started. Please try again in a minute.");
            }
        });
        server.createContext("/arionpay/webhook", ex -> {
            if (!ex.getRequestMethod().equals("POST")) {
                reply(ex, 405, "POST only");
                return;
            }
            byte[] raw = ex.getRequestBody().readAllBytes(); // the exact bytes: check before parsing
            Event event;
            try {
                event = arionpay.verifyWebhook(raw, ex.getRequestHeaders().getFirst("ArionPay-Signature"));
            } catch (SignatureException e) {
                reply(ex, 401, "invalid signature");
                return;
            }
            if (Webhooks.isTestEvent(event)) { // "Send test webhook": a made-up invoice
                reply(ex, 200, "ok");
                return;
            }
            Invoice inv = event.getInvoice();
            if (inv.isTest() != testMode) {
                reply(ex, 409, "test/live mismatch");
                return;
            }
            if (PROCESSED.add(event.getId())) {
                Order order = ORDERS.get(inv.getOrderId());
                if (event.getType().equals("invoice.paid") && order != null && inv.matches(order.total(), order.currency())
                        && PAID.add(inv.getOrderId())) {
                    // deliver here
                    System.err.println("order " + inv.getOrderId() + " paid with " + inv.getPaidCrypto() + " " + inv.getAsset() + " (invoice " + inv.getId() + ")");
                }
            }
            reply(ex, 200, "ok");
        });
        server.start();
        System.err.println("listening on 127.0.0.1:" + port);
    }

    static void reply(HttpExchange ex, int status, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
