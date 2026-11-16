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
    private final IdempotencyStateService idempotencyStateService;

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

        Account source = accountRepository
                .findByAccountNumber(sourceAccountNumber);

        if (source == null) {
            throw new BusinessException("Source account not found");
        }

        Account destination = accountRepository
                .findByAccountNumber(destinationAccountNumber);

        if (destination == null) {
            throw new BusinessException("Destination account not found");
        }

        Long firstId = Math.min(source.getId(), destination.getId());
        Long secondId = Math.max(source.getId(), destination.getId());

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new BusinessException("Account not found"));

        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new BusinessException("Account not found"));

        Account lockedSource = first.getId().equals(source.getId()) ? first : second;
        Account lockedDestination = first.getId().equals(destination.getId()) ? first : second;

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

    @Transactional
    public Transaction executeTransfer(
            Long userId,
            String sourceAccountNumber,
            String destinationAccountNumber,
            BigDecimal amount,
            Long idempotencyRecordId
    ){
        validateAmount(amount);

        Account[] accounts = lockAccounts(
                sourceAccountNumber,
                destinationAccountNumber
        );

        Account sourceAccount = accounts[0];
        Account destinationAccount = accounts[1];

        if (!sourceAccount.getUser().getId().equals(userId)) {
            throw new BusinessException("Source account does not belong to user");
        }

        if (sourceAccount.getId().equals(destinationAccount.getId())) {
            throw new BusinessException("Cannot transfer to the same account");
        }

        validateAccounts(sourceAccount, destinationAccount);

        validateDailyTransferLimit(sourceAccount, amount);

        validateSufficientFunds(sourceAccount, amount);

        sourceAccount.setBalance(
                sourceAccount.getBalance().subtract(amount)
        );

        destinationAccount.setBalance(
                destinationAccount.getBalance().add(amount)
        );
        Transaction transaction = new Transaction();

        transaction.setReference(UUID.randomUUID().toString());
        transaction.setAmount(amount);
        transaction.setSourceAccount(sourceAccount);
        transaction.setDestinationAccount(destinationAccount);
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setFailureReason(null);

        Transaction savedTransaction = transactionRepository.save(transaction);

        idempotencyStateService.markCompleted(
                idempotencyRecordId,
                savedTransaction
        );

        return savedTransaction;

    }
}
