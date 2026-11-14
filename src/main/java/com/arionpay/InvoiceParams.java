package com.arionpay;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to put on a new invoice. Only the amount is required; fields you do not set are not sent.
 *
 * <pre>{@code
 * new InvoiceParams("49.90").currency("USD").orderId("1001").successUrl("https://shop.example.com/thanks/1001")
 * }</pre>
 */
public final class InvoiceParams {
    private final String amount;
    private String currency = "USD";
    private String orderId;
    private String description;
    private String customerEmail;
    private String successUrl;
    private String cancelUrl;
    private String callbackUrl;
    private String asset;
    private List<String> allowedAssets;
    private Integer expiresMin;
    private Map<String, Object> metadata;
    private String idempotencyKey;
    private Boolean test;

    /** @param amount the price, e.g. "49.90" */
    public InvoiceParams(String amount) {
        if (amount == null || amount.trim().isEmpty()) {
            throw new IllegalArgumentException("amount is required.");
        }
        this.amount = amount.trim();
    }

    /** @param amount the price, e.g. new BigDecimal("49.90") */
    public InvoiceParams(BigDecimal amount) {
        this(amount == null ? null : amount.toPlainString());
    }

    /** Price currency (default USD): USD, EUR, GBP, CAD, AUD, CHF, JPY, TRY, AED, INR, BRL, ... */
    public InvoiceParams currency(String v) { this.currency = v; return this; }
    /** Your order number; comes back as {@link Invoice#getOrderId()}. */
    public InvoiceParams orderId(String v) { this.orderId = v; return this; }
    public InvoiceParams description(String v) { this.description = v; return this; }
    public InvoiceParams customerEmail(String v) { this.customerEmail = v; return this; }
    /** Where the customer goes after paying. Coming back here does NOT prove payment. */
    public InvoiceParams successUrl(String v) { this.successUrl = v; return this; }
    public InvoiceParams cancelUrl(String v) { this.cancelUrl = v; return this; }
    /** Where ArionPay sends this invoice's webhooks (default: your store's webhook address). */
    public InvoiceParams callbackUrl(String v) { this.callbackUrl = v; return this; }
    /** Pre-select a coin, e.g. "USDT_TRC20". */
    public InvoiceParams asset(String v) { this.asset = v; return this; }
    public InvoiceParams allowedAssets(List<String> v) { this.allowedAssets = v == null ? null : new ArrayList<>(v); return this; }
    /** Minutes until the invoice expires (5 to 1440). */
    public InvoiceParams expiresMin(int v) { this.expiresMin = v; return this; }
    /** Your data (strings, numbers, booleans, maps, lists); sent back in every webhook of this invoice. */
    public InvoiceParams metadata(Map<String, Object> v) { this.metadata = v == null ? null : new LinkedHashMap<>(v); return this; }
    /** The same key always returns the same invoice (a random one is used when you set none). */
    public InvoiceParams idempotencyKey(String v) { this.idempotencyKey = v; return this; }
    /** true for a test invoice (no real money). */
    public InvoiceParams test(boolean v) { this.test = v; return this; }

    String getAmount() { return amount; }
    String getCurrency() { return currency; }
    String getOrderId() { return orderId; }
    String getIdempotencyKey() { return idempotencyKey; }

    Map<String, Object> toBody(String defaultIdempotencyKey) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amount", amount);
        b.put("currency", currency == null ? "USD" : currency);
        put(b, "orderId", orderId);
        put(b, "description", description);
        put(b, "customerEmail", customerEmail);
        put(b, "successUrl", successUrl);
        put(b, "cancelUrl", cancelUrl);
        put(b, "callbackUrl", callbackUrl);
        put(b, "asset", asset);
        put(b, "allowedAssets", allowedAssets);
        put(b, "expiresMin", expiresMin);
        put(b, "metadata", metadata);
        b.put("idempotencyKey", idempotencyKey != null && !idempotencyKey.isEmpty() ? idempotencyKey : defaultIdempotencyKey);
        put(b, "test", test);
        return b;
    }

    private static void put(Map<String, Object> b, String k, Object v) {
        if (v != null) {
            b.put(k, v);
        }
    }
}
