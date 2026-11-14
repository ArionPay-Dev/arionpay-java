package com.arionpay;

/** A webhook must not be trusted: bad or stale signature, wrong secret, or not an ArionPay event. Answer 401. */
public class SignatureException extends ArionPayException {
    private static final long serialVersionUID = 1L;

    public SignatureException(String message) {
        super(message);
    }
}
