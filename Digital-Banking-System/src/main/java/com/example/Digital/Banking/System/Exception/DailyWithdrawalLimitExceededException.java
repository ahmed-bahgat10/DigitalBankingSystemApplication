package com.example.Digital.Banking.System.Exception;

public class DailyWithdrawalLimitExceededException extends RuntimeException {
    public DailyWithdrawalLimitExceededException(String message) {
        super(message);
    }
}
