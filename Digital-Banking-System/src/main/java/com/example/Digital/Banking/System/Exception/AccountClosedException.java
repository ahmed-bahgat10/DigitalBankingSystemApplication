package com.example.Digital.Banking.System.Exception;

public class AccountClosedException extends RuntimeException {
    public AccountClosedException(String message) {
        super(message);
    }
}
