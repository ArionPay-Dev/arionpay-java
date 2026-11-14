package com.arionpay;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

/** The answer of {@link ArionPay#createInvoice(InvoiceParams)}. Send the customer to {@link #getInvoiceUrl()}. */
public final class CreatedInvoice {
    private final String id;
    private final String invoiceUrl;
    private final String status;
    private final BigDecimal amountFiat;
    private final String currency;
    private final String asset;
    private final String address;
    private final String amountCrypto;
    private final String expiresAt;
    private final String orderId;
    private final Map<String, Object> raw;

    @SuppressWarnings("unchecked")
    CreatedInvoice(Map<String, Object> r, InvoiceParams p) {
        Map<String, Object> d = r.get("data") instanceof Map ? (Map<String, Object>) r.get("data") : r;
        this.raw = Collections.unmodifiableMap(r);
        this.id = Invoice.str(d.get("id") != null ? d.get("id") : r.get("id"));
        this.invoiceUrl = Invoice.str(d.get("invoice_url") != null ? d.get("invoice_url") : r.get("invoice_url"));
        this.status = d.get("status") != null ? Invoice.str(d.get("status")) : "new";
        this.amountFiat = Invoice.decimal(d.get("amount_fiat"));
        this.currency = d.get("currency") != null ? Invoice.str(d.get("currency")) : p.getCurrency();
        this.asset = Invoice.str(d.get("asset"));
        this.address = Invoice.str(d.get("address"));
        this.amountCrypto = Invoice.str(d.get("amount_crypto"));
        this.expiresAt = Invoice.str(d.get("expires_at"));
        this.orderId = d.containsKey("order_id") ? Invoice.str(d.get("order_id")) : (p.getOrderId() == null ? "" : p.getOrderId());
    }

    /** 24-character invoice id: keep it with your order. */
    public String getId() { return id; }
    /** The payment page to send the customer to. */
    public String getInvoiceUrl() { return invoiceUrl; }
    public String getStatus() { return status; }
    public BigDecimal getAmountFiat() { return amountFiat; }
    public String getCurrency() { return currency; }
    /** The coin when one was pre-selected ("" otherwise). */
    public String getAsset() { return asset; }
    public String getAddress() { return address; }
    public String getAmountCrypto() { return amountCrypto; }
    public String getExpiresAt() { return expiresAt; }
    public String getOrderId() { return orderId; }
    /** The whole answer as ArionPay sent it. */
    public Map<String, Object> getRaw() { return raw; }

    @Override
    public String toString() {
        return "CreatedInvoice{id=" + id + ", invoiceUrl=" + invoiceUrl + ", status=" + status + "}";
    }
}
