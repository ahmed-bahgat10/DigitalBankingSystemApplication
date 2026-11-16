package com.example.Digital.Banking.System.Exception;

public class DailyTransferLimitExceededException extends RuntimeException {
    public DailyTransferLimitExceededException(String message) {
        super(message);
    }
}
