package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.FailureReason;

public record TransferResult(
        Transaction transaction,
        FailureReason failureReason
) {

    public static TransferResult completed(Transaction transaction) {
        return new TransferResult(transaction, null);
    }

    public static TransferResult failed(
            Transaction transaction,
            FailureReason failureReason
    ) {
        return new TransferResult(transaction, failureReason);
    }

    public boolean isSuccessful() {
        return failureReason == null;
    }
}
