package com.arionpay;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ArionPay merchant API client: create crypto invoices, read them, and verify webhooks. Payments go straight to the
 * merchant's own wallet. Use it on your server only: it holds your API secret. Safe to share between threads.
 *
 * <pre>{@code
 * ArionPay arionpay = ArionPay.builder()
 *     .apiKey(System.getenv("ARIONPAY_API_KEY"))
 *     .apiSecret(System.getenv("ARIONPAY_API_SECRET"))
 *     .webhookSecret(System.getenv("ARIONPAY_WEBHOOK_SECRET"))
 *     .build();
 * CreatedInvoice invoice = arionpay.createInvoice(new InvoiceParams("49.90").currency("USD").orderId("1001"));
 * // send the customer to invoice.getInvoiceUrl(); deliver when the invoice.paid webhook arrives
 * }</pre>
 */
public final class ArionPay {
    /** Version of this library. */
    public static final String VERSION = "1.0.0";
    /** ArionPay's merchant API. */
    public static final String DEFAULT_BASE_URL = "https://merchant.arionpay.com";
    /** Scenarios for {@link #simulatePayment(String, String)} (test invoices only). */
    public static final List<String> SCENARIOS = Collections.unmodifiableList(Arrays.asList(
            "detected", "confirm", "exact", "underpaid_1pct", "underpaid_5pct", "overpaid", "partial_half", "late"));

    private static final Pattern PK = Pattern.compile("pk_[A-Za-z0-9_-]{8,64}");
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{24}");

    /** One HTTP exchange; replaceable in tests. */
    interface Transport {
        Response send(String method, String url, Map<String, String> headers, byte[] body, Duration timeout) throws IOException, InterruptedException;
    }

    static final class Response {
        final int status;
        final Map<String, String> headers; // lower-case names
        final byte[] body;

        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    interface Clock {
        long nowUnix();
    }

    private final String apiKey;
    private final String apiSecret;
    private final String webhookSecret;
    private final String origin;
    private final String basePath;
    private final Duration timeout;
    private final int maxAttempts;
    private final String userAgent;
    Transport transport;
    Sleeper sleeper = Thread::sleep;
    Clock clock = () -> System.currentTimeMillis() / 1000;

    private ArionPay(Builder b) {
        if (b.apiKey == null || !PK.matcher(b.apiKey).matches()) {
            throw new IllegalArgumentException("apiKey must be your store API key (it starts with pk_).");
        }
        if (b.apiSecret == null || b.apiSecret.isEmpty()) {
            throw new IllegalArgumentException("apiSecret is required (your store API secret, it starts with sk_).");
        }
        URI u;
        try {
            u = URI.create(b.baseUrl == null || b.baseUrl.isEmpty() ? DEFAULT_BASE_URL : b.baseUrl);
        } catch (IllegalArgumentException e) {
            u = null;
        }
        if (u == null || u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https")) || u.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an http(s) address.");
        }
        this.apiKey = b.apiKey;
        this.apiSecret = b.apiSecret;
        this.webhookSecret = b.webhookSecret == null ? "" : b.webhookSecret;
        this.origin = u.getScheme() + "://" + u.getRawAuthority();
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        this.basePath = path.replaceAll("/+$", "");
        this.timeout = b.timeout;
        this.maxAttempts = Math.max(1, Math.min(5, b.maxAttempts));
        this.userAgent = "arionpay-java/" + VERSION + " java/" + System.getProperty("java.version") + (b.appInfo != null && !b.appInfo.isEmpty() ? " " + b.appInfo : "");
        HttpClient http = b.httpClient != null ? b.httpClient : HttpClient.newBuilder()
                .connectTimeout(b.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.transport = (method, url, headers, body, t) -> {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(t);
            headers.forEach(rb::header);
            rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, String> h = new LinkedHashMap<>();
            res.headers().map().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
            return new Response(res.statusCode(), h, res.body());
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Settings of a client. apiKey and apiSecret are required. */
    public static final class Builder {
        private String apiKey;
        private String apiSecret;
        private String webhookSecret;
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration timeout = Duration.ofSeconds(20);
        private int maxAttempts = 3;
        private String appInfo;
        private HttpClient httpClient;

        private Builder() {}

        /** pk_... (ArionPay dashboard &gt; your store). */
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        /** sk_... */
        public Builder apiSecret(String v) { this.apiSecret = v; return this; }
        /** whsec_... (only needed for {@link ArionPay#verifyWebhook}). */
        public Builder webhookSecret(String v) { this.webhookSecret = v; return this; }
        /** Default https://merchant.arionpay.com. */
        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        /** Per request (default 20 seconds). */
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        /** Tries for safe requests on network errors, 502/503/504 and short 429 (default 3, max 5). */
        public Builder maxAttempts(int v) { this.maxAttempts = v; return this; }
        /** Added to the User-Agent, e.g. "myshop/2.1". */
        public Builder appInfo(String v) { this.appInfo = v; return this; }
        /** Your own HttpClient (proxy, SSL settings). It should not follow redirects. */
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }

        public ArionPay build() {
            return new ArionPay(this);
        }
    }

    // ---------------------------------------------------------------------------------------------------- invoices

    /**
     * Creates an invoice. Send the customer to {@link CreatedInvoice#getInvoiceUrl()}.
     *
     * <p>Safe to retry: every call carries an idempotency key (a random one when you set none), so a repeat after a
     * network error returns the same invoice. The customer coming back to the success URL does NOT prove payment:
     * wait for the invoice.paid webhook.
     */
    public CreatedInvoice createInvoice(InvoiceParams params) {
        if (params == null) {
            throw new IllegalArgumentException("params are required.");
        }
        Map<String, Object> body = params.toBody("sdk_" + UUID.randomUUID());
        return new CreatedInvoice(request("POST", "/api/v1/invoices", body, true, true), params);
    }

    /** Reads an invoice, signed, straight from ArionPay (webhook field names). Use it to double-check, or to poll. */
    public Invoice getInvoice(String invoiceId) {
        checkId(invoiceId);
        return Invoice.fromRead(request("GET", "/api/v1/invoices/" + invoiceId, null, true, true));
    }

    /**
     * Sandbox: moves a TEST invoice through a payment scenario (see {@link #SCENARIOS}); webhooks then fire as for a
     * real payment. Create the test invoice with an asset (e.g. "USDT_TRC20") so a coin is chosen.
     */
    public Map<String, Object> simulatePayment(String invoiceId, String scenario) {
        checkId(invoiceId);
        if (!SCENARIOS.contains(scenario)) {
            throw new IllegalArgumentException("scenario must be one of: " + String.join(", ", SCENARIOS));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenario", scenario);
        return request("POST", "/api/v1/invoices/" + invoiceId + "/simulate", body, false, true);
    }

    /** Asks ArionPay to send a signed sample event to your store's webhook address. It never describes a real order. */
    public Map<String, Object> sendTestWebhook(String eventType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", eventType == null || eventType.isEmpty() ? "invoice.paid" : eventType);
        return request("POST", "/api/v1/webhooks/test", body, false, true);
    }

    /** Coins you can use as asset / allowedAssets ("USDT_TRC20", "BTC", "LTC", ...), with USD rates. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> currencies() {
        Object list = request("GET", "/api/v1/currencies", null, true, false).get("currencies");
        List<Map<String, Object>> out = new ArrayList<>();
        if (list instanceof List) {
            for (Object x : (List<Object>) list) {
                if (x instanceof Map) {
                    out.add((Map<String, Object>) x);
                }
            }
        }
        return out;
    }

    /** Current coin prices in one fiat currency. */
    public Map<String, Object> rates(String currency) {
        String c = currency == null || currency.isEmpty() ? "USD" : currency;
        return request("GET", "/api/v1/rates?currency=" + URLEncoder.encode(c, StandardCharsets.UTF_8), null, true, false);
    }

    /** Checks a webhook with the webhook secret of this client. See {@link Webhooks#verify(byte[], String, String)}. */
    public Event verifyWebhook(byte[] rawBody, String signatureHeader) {
        return Webhooks.verify(rawBody, signatureHeader, webhookSecret, Webhooks.DEFAULT_TOLERANCE_SEC, clock.nowUnix());
    }

    /** {@link #verifyWebhook(byte[], String)} for a body you already hold as text. */
    public Event verifyWebhook(String rawBody, String signatureHeader) {
        return verifyWebhook(rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8), signatureHeader);
    }

    String getUserAgent() {
        return userAgent;
    }

    @Override
    public String toString() {
        return "ArionPay{" + origin + basePath + ", key=" + apiKey + "}"; // never shows the secrets
    }

    // ---------------------------------------------------------------------------------------------------- transport

    private static void checkId(String invoiceId) {
        if (invoiceId == null || !ID.matcher(invoiceId).matches()) {
            throw new IllegalArgumentException("Invoice id must be the 24-character id ArionPay returned.");
        }
    }

    private Map<String, Object> request(String method, String path, Map<String, Object> body, boolean retrySafe, boolean signed) {
        byte[] raw = body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8);
        boolean replayRetryUsed = false;
        for (int attempt = 1; ; attempt++) {
            try {
                return send(method, path, raw, signed);
            } catch (ApiException e) {
                // Two identical signed requests in the same second have the same signature; only the first is accepted.
                if (e.getStatus() == 401 && e.getMessage().toLowerCase(Locale.ROOT).contains("already used") && !replayRetryUsed) {
                    replayRetryUsed = true;
                    pause(1100, e);
                    attempt--;
                    continue;
                }
                long retryAfter = e.getBody().get("retryAfterSec") instanceof Number ? ((Number) e.getBody().get("retryAfterSec")).longValue() : 0;
                int s = e.getStatus();
                boolean temporary = s == 0 || s == 502 || s == 503 || s == 504 || (s == 429 && retryAfter <= 5);
                boolean interrupted = "interrupted".equals(e.getCode());
                if (!retrySafe || !temporary || interrupted || attempt >= maxAttempts) {
                    throw e;
                }
                pause(s == 429 ? Math.max(1, retryAfter) * 1000 : (long) (500 * Math.pow(3, attempt - 1)), e);
            }
        }
    }

    private void pause(long millis, ApiException cause) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String method, String path, byte[] raw, boolean signed) {
        int q = path.indexOf('?');
        String fullPath = basePath + (q < 0 ? path : path.substring(0, q) + "?" + path.substring(q + 1));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", userAgent);
        if (signed) {
            long ts = clock.nowUnix();
            headers.put("x-api-key", apiKey);
            headers.put("x-timestamp", Long.toString(ts));
            headers.put("x-signature", Webhooks.signRequest(apiSecret, ts, method, fullPath, raw));
        }
        if (raw != null) {
            headers.put("Content-Type", "application/json");
        }
        Response res;
        try {
            res = transport.send(method, origin + fullPath, headers, raw, timeout);
        } catch (IOException e) {
            throw new ApiException("Could not reach ArionPay: " + e, 0, "network_error", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted while calling ArionPay.", 0, "interrupted", null, e);
        }
        Map<String, Object> obj = new LinkedHashMap<>();
        if (res.body != null && res.body.length > 0) {
            try {
                Object decoded = Json.parse(res.body);
                if (decoded instanceof Map) {
                    obj = (Map<String, Object>) decoded;
                }
            } catch (Json.ParseException ignored) {
                // an HTML error page from a proxy: keep the HTTP status
            }
        }
        if (res.status >= 200 && res.status < 300) {
            return obj;
        }
        Object err = obj.get("error") instanceof String ? obj.get("error") : obj.get("message");
        String message = err instanceof String ? (String) err : "HTTP " + res.status;
        Map<String, Object> errBody = new LinkedHashMap<>(obj);
        String ra = res.headers == null ? null : res.headers.get("retry-after");
        if (ra != null) {
            try {
                long sec = Long.parseLong(ra.trim());
                if (sec > 0) {
                    errBody.put("retryAfterSec", sec);
                }
            } catch (NumberFormatException ignored) {
                // an HTTP date: not used
            }
        }
        throw new ApiException("ArionPay API error (" + res.status + "): " + message, res.status,
                obj.get("code") instanceof String ? (String) obj.get("code") : "", errBody, null);
    }
}
