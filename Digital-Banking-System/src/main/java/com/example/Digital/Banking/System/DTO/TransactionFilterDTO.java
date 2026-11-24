package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Dates select UTC calendar days, compared with the existing timezone-less createdAt
 * column. No conversion of historical LocalDateTime values is attempted.
 */
public record TransactionFilterDTO(
        TransactionType type,
        TransactionStatus status,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {
    public static TransactionFilterDTO empty() {
        return new TransactionFilterDTO(null, null, null, null, null, null);
    }
}
