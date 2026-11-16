package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;

public record IdempotencyResult(
        IdempotencyStatus status,
        Transaction transaction,
        FailureReason failureReason
) {
}
