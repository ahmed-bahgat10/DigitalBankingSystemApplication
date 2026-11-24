package com.example.Digital.Banking.System.Mapper;

import com.example.Digital.Banking.System.DTO.TransactionResponseDTO;
import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.Currency;
import com.example.Digital.Banking.System.Enum.TransactionDirection;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
public class TransactionMapper {
    public TransactionResponseDTO toResponse(Transaction transaction, Long userId, Long accountId) {
        Account source = transaction.getSourceAccount();
        Account destination = transaction.getDestinationAccount();
        boolean completed = transaction.getStatus() == TransactionStatus.COMPLETED;
        boolean failed = transaction.getStatus() == TransactionStatus.FAILED;
        boolean untrustedAttempt = transaction.getType() == TransactionType.TRANSFER
                && !completed && !ownedBy(source, userId);
        return new TransactionResponseDTO(
                transaction.getReference(), transaction.getType(), direction(transaction, userId, accountId),
                transaction.getAmount(), untrustedAttempt ? null : currency(transaction),
                transaction.getStatus(),
                untrustedAttempt ? null : accountNumber(source, userId),
                untrustedAttempt ? null : accountNumber(destination, userId),
                transaction.getCreatedAt(), completed ? transaction.getCompletedAt() : null,
                failed && !untrustedAttempt ? transaction.getFailureReason() : null,
                failed ? (untrustedAttempt ? "Transfer could not be completed" : safeFailureMessage(transaction)) : null);
    }

    private TransactionDirection direction(Transaction transaction, Long userId, Long accountId) {
        Account source = transaction.getSourceAccount();
        Account destination = transaction.getDestinationAccount();
        if (accountId != null) {
            return source != null && Objects.equals(source.getId(), accountId)
                    ? TransactionDirection.OUTGOING : TransactionDirection.INCOMING;
        }
        if (transaction.getType() == TransactionType.DEPOSIT) {
            return TransactionDirection.INCOMING;
        }
        if (transaction.getType() == TransactionType.WITHDRAW
                || transaction.getStatus() != TransactionStatus.COMPLETED) {
            return TransactionDirection.OUTGOING;
        }
        if (ownedBy(source, userId) && ownedBy(destination, userId)) {
            return TransactionDirection.INTERNAL;
        }
        return ownedBy(source, userId) ? TransactionDirection.OUTGOING : TransactionDirection.INCOMING;
    }

    private boolean ownedBy(Account account, Long userId) {
        return userId != null && account != null && account.getUser() != null
                && userId.equals(account.getUser().getId());
    }

    private String accountNumber(Account account, Long userId) {
        if (account == null || account.getAccountNumber() == null) {
            return null;
        }
        String number = account.getAccountNumber();
        if (ownedBy(account, userId)) {
            return number;
        }
        return number.length() <= 4 ? "****" : "****" + number.substring(number.length() - 4);
    }

    private Currency currency(Transaction transaction) {
        Account source = transaction.getSourceAccount();
        Account destination = transaction.getDestinationAccount();
        if (transaction.getType() == TransactionType.DEPOSIT) {
            return destination == null ? null : destination.getCurrency();
        }
        if (transaction.getType() == TransactionType.WITHDRAW) {
            return source == null ? null : source.getCurrency();
        }
        if (source != null && destination != null
                && source.getCurrency() != destination.getCurrency()) {
            return null;
        }
        return source != null ? source.getCurrency() : destination == null ? null : destination.getCurrency();
    }

    /** Stored exception text is never exposed: even historical rows may contain technical details. */
    private String safeFailureMessage(Transaction transaction) {
        if (transaction.getFailureReason() == null) {
            return "Transaction could not be completed";
        }
        return switch (transaction.getFailureReason()) {
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case ACCOUNT_SUSPENDED -> "Account is suspended";
            case ACCOUNT_CLOSED -> "Account is closed";
            case DAILY_LIMIT_EXCEEDED -> "Daily transaction limit exceeded";
            case INVALID_TRANSFER -> "Transfer could not be completed";
        };
    }
}
