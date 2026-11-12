package com.example.Digital.Banking.System.Exception;

public class InvalidKycTransitionException extends RuntimeException {
    public InvalidKycTransitionException(String message) {
        super(message);
    }
}
