package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Enum.Currency;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.TransactionDirection;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public record TransactionResponseDTO(
        String reference,
        TransactionType type,
        TransactionDirection direction,
        BigDecimal amount,
        Currency currency,
        TransactionStatus status,
        String sourceAccountNumber,
        String destinationAccountNumber,
        LocalDateTime createdAt,
        Instant completedAt,
        FailureReason failureReason,
        String failureMessage
) {}
