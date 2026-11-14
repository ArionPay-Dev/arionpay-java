# ArionPay for Java

Official Java client for [ArionPay](https://arionpay.com). Create crypto invoices, send customers to the payment page, and check webhooks. The money goes straight to your own wallet: ArionPay never holds it.

- No dependencies: only the JDK (`java.net.http`). Java 11 or newer.
- Works with Spring Boot, Jakarta Servlet, Micronaut, Quarkus, Javalin or plain Java. Kotlin too.
- Safe retries: every invoice request carries an idempotency key, so a network error can never create two invoices.
- Amounts are exact: fiat prices are `BigDecimal`, crypto amounts are kept as text, never a rounded `double`.

Maven:

```xml
<dependency>
  <groupId>com.arionpay</groupId>
  <artifactId>arionpay-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle: `implementation("com.arionpay:arionpay-java:1.0.0")`

## Keys

In your ArionPay dashboard, open your store:

| Value | Where | Starts with |
| --- | --- | --- |
| API key | Store page, **API key** | `pk_` |
| API secret | Store page, **API secret** (asks for your authenticator code) | `sk_` |
| Webhook secret | **Webhooks** page, **signing secret** | `whsec_` |

Keep them on your server, for example in environment variables.

## Take a payment

```java
import com.arionpay.*;

ArionPay arionpay = ArionPay.builder()
    .apiKey(System.getenv("ARIONPAY_API_KEY"))
    .apiSecret(System.getenv("ARIONPAY_API_SECRET"))
    .webhookSecret(System.getenv("ARIONPAY_WEBHOOK_SECRET"))
    .build();

CreatedInvoice invoice = arionpay.createInvoice(new InvoiceParams("49.90")
    .currency("USD")
    .orderId("1001")
    .successUrl("https://shop.example.com/thanks/1001")
    .cancelUrl("https://shop.example.com/cart")
    .metadata(Map.of("userId", 42)));
// send the customer to invoice.getInvoiceUrl()
```

The customer coming back to the success URL does **not** prove they paid. Deliver the order when the `invoice.paid` webhook arrives (or when `getInvoice` says paid).

A webhook's `event.getInvoice()` and the result of `getInvoice(id)` are the same `Invoice` class (`getId()`, `getStatus()`, `getOrderId()`, `getAmountFiat()`, `getCurrency()`, `getAsset()`, `getPaidCrypto()`, `isTest()`, `getMetadata()`, ...), so one method can handle both. `getRaw()` has every field ArionPay sent.

## Webhooks

Set your webhook address on the **Webhooks** page of the dashboard (a public `https://` address), or per invoice with `callbackUrl(...)`. Check the signature against the **raw** body, before you parse it.

### Spring Boot

```java
@RestController
class ArionPayWebhookController {
    private final ArionPay arionpay;

    ArionPayWebhookController(ArionPay arionpay) {
        this.arionpay = arionpay;
    }

    @PostMapping("/arionpay/webhook")
    ResponseEntity<Void> webhook(@RequestBody byte[] body,
                                 @RequestHeader(value = "ArionPay-Signature", required = false) String signature) {
        Event event;
        try {
            event = arionpay.verifyWebhook(body, signature);
        } catch (SignatureException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!Webhooks.isTestEvent(event)) { // "Send test webhook": not a real order
            handleEvent(event);
        }
        return ResponseEntity.ok().build();
    }
}
```

Take the body as `byte[]`. A `Map` or your own class is parsed and re-encoded JSON, and its signature never matches. If you use Spring Security, leave this path out of CSRF protection.

Make the client a bean once (it is safe to share between threads):

```java
@Bean
ArionPay arionPay(@Value("${arionpay.api-key}") String key, @Value("${arionpay.api-secret}") String secret,
                  @Value("${arionpay.webhook-secret}") String webhookSecret) {
    return ArionPay.builder().apiKey(key).apiSecret(secret).webhookSecret(webhookSecret).build();
}
```

### Jakarta Servlet

```java
byte[] raw = request.getInputStream().readAllBytes();
Event event = arionpay.verifyWebhook(raw, request.getHeader("ArionPay-Signature")); // throws SignatureException: answer 401
```

No client at hand? `Webhooks.verify(raw, header, webhookSecret)` does the same.

### Handling an event

```java
void handleEvent(Event event) {
    if (processedEvents.contains(event.getId())) { // the same event can arrive more than once
        return;
    }
    Invoice inv = event.getInvoice();
    Order order = orders.findByNumber(inv.getOrderId());
    if (event.getType().equals("invoice.paid") && order != null && !order.isPaid()
            && inv.matches(order.getTotal(), order.getCurrency()) && inv.isTest() == order.isTest()) {
        order.markPaid(inv); // deliver here
    }
    processedEvents.add(event.getId());
}
```

`inv.matches(new BigDecimal("49.90"), "USD")` (or `matches("49.90", "USD")`) is true when the invoice price is that total, to the cent, in that currency.

| Event | Meaning | What to do |
| --- | --- | --- |
| `invoice.pending` | A payment was seen on the network and waits for confirmations. | Show "payment on its way". Do not deliver yet. |
| `invoice.partial` | Less than the full amount arrived. The invoice stays open for the rest. | Do not deliver. |
| `invoice.paid` | Fully paid and confirmed. Also sent when you accept a payment by hand in the dashboard. | Deliver the order. |
| `invoice.expired` | Time ran out without full payment. A late payment can still make it `paid`. | Keep the order unpaid. |
| `invoice.needs_review` | Something needs a person, for example more money arrived after the invoice was paid. | Tell a human. Do not change the order. |

ArionPay sends again when your server does not answer `2xx` (for about 3 days).

### Rules for a safe integration

1. Check the signature on the raw body. Never trust a webhook you could not verify.
2. Deliver only on `invoice.paid` (or when `getInvoice(id).isPaid()`).
3. Match the invoice to your order: your order id or metadata, and the same amount and currency as the order's current total (`inv.matches`).
4. Ignore test events (`Webhooks.isTestEvent(event)`), and in your live shop ignore invoices where `isTest()` is true.
5. Handle each `event.getId()` once, and answer `2xx` quickly.
6. Do not compare crypto amounts yourself. ArionPay decides when an invoice is paid, including payments inside your store's payment tolerance and payments you accept in Payment Radar.

## Test without real money

```java
CreatedInvoice inv = arionpay.createInvoice(new InvoiceParams("10").orderId("test-1").asset("USDT_TRC20").test(true));
arionpay.simulatePayment(inv.getId(), "exact"); // your webhook gets invoice.paid, like a real payment
```

Scenarios: `exact`, `detected`, `confirm`, `underpaid_1pct`, `underpaid_5pct`, `overpaid`, `partial_half`, `late`.

`arionpay.sendTestWebhook("invoice.paid")` sends a signed sample event to your store's webhook address. It describes a made-up invoice (`inv_test_...`) and must never change an order.

A complete small shop on the JDK's own HTTP server is in [`examples/ShopServer.java`](examples/ShopServer.java) (`java -cp arionpay-java-1.0.0.jar examples/ShopServer.java`, Java 17 or newer for the example).

## Reference

| Method | Returns |
| --- | --- |
| `createInvoice(InvoiceParams)` | `CreatedInvoice`: `getId()`, `getInvoiceUrl()`, `getStatus()`, `getAmountFiat()`, `getCurrency()`, `getAsset()`, `getAddress()`, `getAmountCrypto()`, `getExpiresAt()`, `getOrderId()`, `getRaw()` |
| `getInvoice(id)` | `Invoice`, with the webhook field names. |
| `simulatePayment(id, scenario)` | Sandbox only (test invoices). |
| `sendTestWebhook(eventType)` | `Map` with `delivered`, `httpStatus`, `eventId` |
| `currencies()` | Coins you can use as `asset` / `allowedAssets`. |
| `rates(currency)` | Current coin prices. |
| `verifyWebhook(rawBody, signatureHeader)` | `Event`, or throws `SignatureException`. Also `Webhooks.verify(rawBody, header, secret)`. |

`InvoiceParams`: `new InvoiceParams(amount)` then `currency`, `orderId`, `description`, `customerEmail`, `successUrl`, `cancelUrl`, `callbackUrl`, `asset`, `allowedAssets`, `expiresMin` (5 to 1440), `metadata`, `idempotencyKey`, `test`. Fields you do not set are not sent.

Errors from ArionPay are `ApiException` (unchecked) with `getStatus()` (HTTP status, `0` when ArionPay could not be reached), `getCode()` (for example `idempotency_key_reused`, `rate_limited`, `unknown_asset`) and `getMessage()`. Messages never contain your secret. Bad arguments throw `IllegalArgumentException` before anything is sent.

`ArionPay.builder()`: `apiKey`, `apiSecret`, `webhookSecret`, `baseUrl` (default `https://merchant.arionpay.com`), `timeout` (default 20 seconds), `maxAttempts` (default 3), `appInfo` (added to the User-Agent), `httpClient` (your own `java.net.http.HttpClient`, for a proxy; it should not follow redirects).

## Build and test

```bash
mvn test                    # unit tests; the live test is skipped
ARIONPAY_IT_BASE_URL=... ARIONPAY_IT_API_KEY=pk_... ARIONPAY_IT_API_SECRET=sk_... \
ARIONPAY_IT_WEBHOOK_SECRET=whsec_... mvn test -Dtest=IntegrationTest
mvn -P release package      # jar + sources + javadoc, as Maven Central wants
```

## License

MIT
