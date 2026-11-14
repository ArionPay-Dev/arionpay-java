package com.arionpay;

import java.util.Collections;
import java.util.Map;

/** A verified ArionPay webhook, from {@link Webhooks#verify} or {@link ArionPay#verifyWebhook}. */
public final class Event {
    private final String id;
    private final String type;
    private final long created;
    private final boolean livemode;
    private final boolean test;
    private final Invoice invoice;
    private final Map<String, Object> raw;

    Event(Map<String, Object> raw, Invoice invoice) {
        this.raw = Collections.unmodifiableMap(raw);
        this.id = (String) raw.get("id");
        this.type = (String) raw.get("type");
        this.created = raw.get("created") instanceof Long ? (Long) raw.get("created") : 0L;
        this.livemode = Boolean.TRUE.equals(raw.get("livemode"));
        this.test = Boolean.TRUE.equals(raw.get("test"));
        this.invoice = invoice;
    }

    /** Event id ("evt_..."): handle each one once. */
    public String getId() { return id; }
    /** invoice.pending, invoice.partial, invoice.paid, invoice.expired or invoice.needs_review. */
    public String getType() { return type; }
    public long getCreated() { return created; }
    public boolean isLivemode() { return livemode; }
    /** Set on events made by "Send test webhook"; see {@link Webhooks#isTestEvent(Event)}. */
    public boolean isTest() { return test; }
    public Invoice getInvoice() { return invoice; }
    /** The whole event as ArionPay sent it. */
    public Map<String, Object> getRaw() { return raw; }

    @Override
    public String toString() {
        return "Event{id=" + id + ", type=" + type + ", invoice=" + invoice + "}";
    }
}
