package com.arionpay;

/** Base class of every error this library throws (bad arguments throw {@link IllegalArgumentException}). */
public class ArionPayException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ArionPayException(String message) {
        super(message);
    }

    public ArionPayException(String message, Throwable cause) {
        super(message, cause);
    }
}
