package com.arionpay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An invoice with the field names of the webhook's {@code data.invoice}: the same class for webhooks
 * ({@link Event#getInvoice()}) and signed reads ({@link ArionPay#getInvoice(String)}).
 *
 * <p>Crypto amounts are kept as text, exactly as ArionPay sent them. {@link #getRaw()} has every field.
 */
public final class Invoice {
    private final Map<String, Object> raw;
    private final String id;
    private final String status;
    private final String orderId;
    private final BigDecimal amountFiat;
    private final String currency;
    private final String asset;
    private final String network;
    private final String amountCrypto;
    private final String paidCrypto;
    private final String confirmedCrypto;
    private final boolean overpaid;
    private final boolean underpaidWithinTolerance;
    private final boolean late;
    private final boolean needsReview;
    private final String reviewReason;
    private final boolean manual;
    private final boolean test;
    private final List<Object> transactions;
    private final Map<String, Object> metadata;
    private final String paidAt;
    private final String txHash;

    private Invoice(Map<String, Object> raw, Map<String, Object> f) {
        this.raw = Collections.unmodifiableMap(raw);
        this.id = str(f.get("id"));
        this.status = str(f.get("status")).toLowerCase(java.util.Locale.ROOT);
        this.orderId = str(f.get("orderId"));
        this.amountFiat = decimal(f.get("amountFiat"));
        this.currency = str(f.get("currency"));
        this.asset = str(f.get("asset"));
        this.network = str(f.get("network"));
        this.amountCrypto = str(f.get("amountCrypto"));
        this.paidCrypto = str(f.get("paidCrypto"));
        this.confirmedCrypto = str(f.get("confirmedCrypto"));
        this.overpaid = Boolean.TRUE.equals(f.get("overpaid"));
        this.underpaidWithinTolerance = Boolean.TRUE.equals(f.get("underpaidWithinTolerance"));
        this.late = Boolean.TRUE.equals(f.get("late"));
        this.needsReview = Boolean.TRUE.equals(f.get("needsReview"));
        this.reviewReason = str(f.get("reviewReason"));
        this.manual = Boolean.TRUE.equals(f.get("manual"));
        this.test = Boolean.TRUE.equals(f.get("test"));
        this.transactions = f.get("transactions") instanceof List ? Collections.unmodifiableList(new ArrayList<>((List<?>) f.get("transactions"))) : Collections.emptyList();
        this.metadata = f.get("metadata") instanceof Map ? Collections.unmodifiableMap(stringKeys((Map<?, ?>) f.get("metadata"))) : Collections.emptyMap();
        this.paidAt = str(f.get("paidAt"));
        this.txHash = str(f.get("txHash"));
    }

    /** From a webhook's data.invoice (already in webhook field names). Odd field types become empty, never errors. */
    static Invoice fromWebhook(Map<String, Object> m) {
        return new Invoice(m, m);
    }

    /** From the signed read (GET /api/v1/invoices/&lt;id&gt;), renamed to the webhook field names. */
    static Invoice fromRead(Map<String, Object> r) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", r.get("invoiceId") != null ? r.get("invoiceId") : r.get("id"));
        f.put("status", r.get("status"));
        f.put("orderId", r.get("orderId"));
        f.put("amountFiat", r.get("amountFiat"));
        f.put("currency", r.get("currency"));
        f.put("asset", r.get("settledCurrency"));
        f.put("network", r.get("network"));
        f.put("amountCrypto", r.get("amountExpected"));
        f.put("paidCrypto", r.get("amountReceived") != null ? r.get("amountReceived") : "0");
        f.put("confirmedCrypto", r.get("amountConfirmed") != null ? r.get("amountConfirmed") : "0");
        for (String k : new String[] {"overpaid", "underpaidWithinTolerance", "late", "needsReview", "reviewReason", "manual", "test", "transactions", "metadata", "txHash"}) {
            f.put(k, r.get(k));
        }
        f.put("paidAt", r.get("settledAt"));
        return new Invoice(r, f);
    }

    static String str(Object v) {
        if (v == null || v instanceof Map || v instanceof List) {
            return "";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        return v.toString();
    }

    static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Long || v instanceof Integer) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof String) {
            try {
                return new BigDecimal(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * True when the invoice price is exactly this order total (to the cent) in this currency: the check to make
     * before you deliver. Crypto amounts never need comparing: ArionPay decides whether an invoice is paid.
     */
    public boolean matches(BigDecimal orderTotal, String orderCurrency) {
        if (amountFiat == null || orderTotal == null || orderCurrency == null) {
            return false;
        }
        return amountFiat.setScale(2, RoundingMode.HALF_UP).compareTo(orderTotal.setScale(2, RoundingMode.HALF_UP)) == 0
                && currency.equalsIgnoreCase(orderCurrency);
    }

    /** {@link #matches(BigDecimal, String)} with the total as text, e.g. {@code matches("49.90", "USD")}. */
    public boolean matches(String orderTotal, String orderCurrency) {
        BigDecimal total = decimal(orderTotal);
        return total != null && matches(total, orderCurrency);
    }

    /** 24-character invoice id (or "inv_test_..." in a sample event). */
    public String getId() { return id; }
    /** new, pending, paid, expired, ... (lower case). */
    public String getStatus() { return status; }
    /** True when ArionPay counts this invoice as fully paid. */
    public boolean isPaid() { return "paid".equals(status); }
    /** Your order id from {@link InvoiceParams#orderId(String)} ("" when none). */
    public String getOrderId() { return orderId; }
    /** The invoice price in {@link #getCurrency()}; null when missing. */
    public BigDecimal getAmountFiat() { return amountFiat; }
    public String getCurrency() { return currency; }
    /** The coin, e.g. "USDT_TRC20" ("" before one is chosen). */
    public String getAsset() { return asset; }
    public String getNetwork() { return network; }
    public String getAmountCrypto() { return amountCrypto; }
    public String getPaidCrypto() { return paidCrypto; }
    public String getConfirmedCrypto() { return confirmedCrypto; }
    public boolean isOverpaid() { return overpaid; }
    /** Paid slightly under, inside the store's payment tolerance (still paid). */
    public boolean isUnderpaidWithinTolerance() { return underpaidWithinTolerance; }
    /** Paid after the invoice expired. */
    public boolean isLate() { return late; }
    public boolean isNeedsReview() { return needsReview; }
    public String getReviewReason() { return reviewReason; }
    /** The merchant accepted this payment by hand in the dashboard. */
    public boolean isManual() { return manual; }
    /** A test invoice (no real money). */
    public boolean isTest() { return test; }
    public List<Object> getTransactions() { return transactions; }
    /** Your data from {@link InvoiceParams#metadata(Map)}. */
    public Map<String, Object> getMetadata() { return metadata; }
    public String getPaidAt() { return paidAt; }
    public String getTxHash() { return txHash; }
    /** Every field as ArionPay sent it (also customerEmail, createdAt, expiresAt, confirmations, ...). */
    public Map<String, Object> getRaw() { return raw; }

    @Override
    public String toString() {
        return "Invoice{id=" + id + ", status=" + status + ", orderId=" + orderId + ", amountFiat=" + amountFiat + " " + currency + ", asset=" + asset + ", test=" + test + "}";
    }
}
