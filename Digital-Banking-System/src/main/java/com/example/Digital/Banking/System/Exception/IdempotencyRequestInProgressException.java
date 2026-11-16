package com.example.Digital.Banking.System.Exception;

public class IdempotencyRequestInProgressException extends RuntimeException {
    public IdempotencyRequestInProgressException(String message) {
        super(message);
    }
}
