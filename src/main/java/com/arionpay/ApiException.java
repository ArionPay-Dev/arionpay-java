package com.arionpay;

import java.util.Collections;
import java.util.Map;

/** ArionPay answered with an error, or could not be reached ({@link #getStatus()} 0). Never contains your secret. */
public class ApiException extends ArionPayException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final transient Map<String, Object> body;

    public ApiException(String message, int status, String code, Map<String, Object> body, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code == null ? "" : code;
        this.body = body == null ? Collections.emptyMap() : Collections.unmodifiableMap(body);
    }

    /** HTTP status, 0 when ArionPay could not be reached. */
    public int getStatus() {
        return status;
    }

    /** ArionPay error code, e.g. "idempotency_key_reused", "rate_limited", "unknown_asset" ("" when none). */
    public String getCode() {
        return code;
    }

    /** Decoded error body ("retryAfterSec" added from a Retry-After header). */
    public Map<String, Object> getBody() {
        return body;
    }
}
