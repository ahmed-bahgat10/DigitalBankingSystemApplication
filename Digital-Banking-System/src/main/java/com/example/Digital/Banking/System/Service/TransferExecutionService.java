package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Entity.TransferProperties;
import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import com.example.Digital.Banking.System.Exception.*;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
import com.example.Digital.Banking.System.DTO.TransferResult;
import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Entity.IdempotencyProperties;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import com.example.Digital.Banking.System.Repository.UserRepository;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferExecutionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransferProperties transferProperties;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final UserRepository userRepository;
    private final IdempotencyProperties idempotencyProperties;

    private void validateAmount(BigDecimal amount) {

        if (amount == null) {
            throw new BusinessException("Amount is required");
        }

        if (amount.compareTo(transferProperties.getMinAmount()) < 0) {
            throw new BusinessException("Amount is below minimum limit");
        }

        if (amount.compareTo(transferProperties.getMaxAmount()) > 0) {
            throw new BusinessException("Amount exceeds maximum limit");
        }

        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException("Amount cannot have more than 2 decimal places");
        }
    }

    private Account[] lockAccounts(
            String sourceAccountNumber,
            String destinationAccountNumber
    ) {
        if (sourceAccountNumber == null || sourceAccountNumber.isBlank()) {
            throw new BusinessException("Source account number is required");
        }

        if (destinationAccountNumber == null || destinationAccountNumber.isBlank()) {
            throw new BusinessException("Destination account number is required");
        }
        sourceAccountNumber = sourceAccountNumber.trim();
        destinationAccountNumber = destinationAccountNumber.trim();

        if (sourceAccountNumber.equals(destinationAccountNumber)) {
            throw new BusinessException("Cannot transfer to the same account");
        }

        // Scalar lookups avoid putting stale account versions in the persistence context.
        Long sourceId = accountRepository.findIdByAccountNumber(sourceAccountNumber)
                .orElseThrow(() -> new BusinessException("Source account not found"));
        Long destinationId = accountRepository.findIdByAccountNumber(destinationAccountNumber)
                .orElseThrow(() -> new BusinessException("Destination account not found"));
        Long firstId = Math.min(sourceId, destinationId);
        Long secondId = Math.max(sourceId, destinationId);

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new BusinessException("Account not found"));

        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new BusinessException("Account not found"));

        Account lockedSource = first.getId().equals(sourceId) ? first : second;
        Account lockedDestination = first.getId().equals(destinationId) ? first : second;

        // Verify that the locked accounts are still the requested accounts.
        if (!lockedSource.getAccountNumber().equals(sourceAccountNumber) ||
                !lockedDestination.getAccountNumber().equals(destinationAccountNumber)) {
            throw new BusinessException("Account information changed");
        }

        return new Account[]{lockedSource, lockedDestination};
    }

    private void validateAccounts(Account source, Account destination) {

        if (source.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException("Source account is suspended");
        }

        if (source.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException("Source account is closed");
        }

        if (destination.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException("Destination account is not available");
        }

        if (destination.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException("Destination account is not available");
        }

        if (source.getCurrency() != destination.getCurrency()) {
            throw new BusinessException("Accounts must have the same currency");
        }
    }

    private void validateDailyTransferLimit(Account source, BigDecimal amount) {

        LocalDate today = LocalDate.now();

        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();

        BigDecimal dailyTotal = transactionRepository.getDailyTransferTotal(
                source.getId(),
                startOfDay,
                endOfDay
        );

        BigDecimal newTotal = dailyTotal.add(amount);

        if (newTotal.compareTo(transferProperties.getDailyLimit()) > 0) {
            throw new DailyTransferLimitExceededException("Daily limit exceeded");
        }
    }

    private void validateSufficientFunds(Account source, BigDecimal amount) {

        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("insufficient funds");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TransferResult executeTransfer(
            Long userId, String sourceAccountNumber, String destinationAccountNumber,
            BigDecimal amount, String idempotencyKey, String requestHash) {
        var now = Instant.now();
        var record = new IdempotencyRecord();
        record.setUser(userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found")));
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        // Provisional value is never committed without the final transaction and result.
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setCreatedAt(now);
        record.setExpiresAt(now.plus(idempotencyProperties.getRetentionHours(),
                ChronoUnit.HOURS));
        // The unique constraint serializes duplicate keys before account locks.
        idempotencyRepository.saveAndFlush(record);

        Account source = null;
        Account destination = null;
        FailureReason reason = null;
        String failureMessage = null;
        try {
            validateAmount(amount);
            Account[] accounts = lockAccounts(sourceAccountNumber, destinationAccountNumber);
            source = accounts[0];
            destination = accounts[1];
            if (!source.getUser().getId().equals(userId)) {
                source = null;
                destination = null;
                throw new BusinessException("Source account does not belong to user");
            }
            validateAccounts(source, destination);
            validateDailyTransferLimit(source, amount);
            validateSufficientFunds(source, amount);
        } catch (InsufficientFundsException ex) {
            reason = FailureReason.INSUFFICIENT_FUNDS;
            failureMessage = ex.getMessage();
        } catch (DailyTransferLimitExceededException ex) {
            reason = FailureReason.DAILY_LIMIT_EXCEEDED;
            failureMessage = ex.getMessage();
        } catch (AccountSuspendedException ex) {
            reason = FailureReason.ACCOUNT_SUSPENDED;
            failureMessage = ex.getMessage();
        } catch (AccountClosedException ex) {
            reason = FailureReason.ACCOUNT_CLOSED;
            failureMessage = ex.getMessage();
        } catch (BusinessException ex) {
            reason = FailureReason.INVALID_TRANSFER;
            failureMessage = ex.getMessage();
        }

        Transaction transaction = new Transaction();
        transaction.setInitiatedBy(record.getUser());
        transaction.setReference(UUID.randomUUID().toString());
        transaction.setAmount(amount);
        transaction.setSourceAccount(source);
        transaction.setDestinationAccount(destination);
        transaction.setType(TransactionType.TRANSFER);
        transaction.setFailureReason(reason);
        transaction.setFailureMessage(failureMessage);
        if (reason == null) {
            source.setBalance(source.getBalance().subtract(amount));
            destination.setBalance(destination.getBalance().add(amount));
            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction.setCompletedAt(Instant.now());
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
        }
        Transaction saved = transactionRepository.save(transaction);
        record.setTransaction(saved);
        record.setFailureReason(reason);
        record.setStatus(reason == null
                ? IdempotencyStatus.COMPLETED
                : IdempotencyStatus.FAILED);
        return reason == null
                ? TransferResult.completed(saved)
                : TransferResult.failed(saved, reason);
    }
}
