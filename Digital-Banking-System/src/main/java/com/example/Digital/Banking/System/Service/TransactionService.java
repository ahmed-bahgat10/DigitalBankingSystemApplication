package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.TransferResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.Digital.Banking.System.Entity.Transaction;

import com.example.Digital.Banking.System.Enum.FailureReason;

import com.example.Digital.Banking.System.Enum.TransactionType;
import com.example.Digital.Banking.System.Exception.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static com.example.Digital.Banking.System.Enum.FailureReason.DAILY_LIMIT_EXCEEDED;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {
    private final FailedTransactionService FailedTransactionService;
    private final WithdrawalExecutionService withdrawalExecutionService;
    private final DepositExecutionService depositExecutionService;
    private final TransferExecutionService transferExecutionService;
    private final IdempotencyService idempotencyService;

    private void recordFailureSafely(
            String accountNumber,
            BigDecimal amount,
            Long userId,
            TransactionType transactionType,
            FailureReason failureReason
    ) {
        try {
            FailedTransactionService.recordFailure(
                    accountNumber,
                    amount,
                    userId,
                    transactionType,
                    failureReason
            );
        } catch (Exception ex) {
            log.error(
                    "Failed to record transaction failure. Type: {}, Reason: {}",
                    transactionType,
                    failureReason,
                    ex
            );
        }
    }
    public Transaction withdraw(
            String accountNumber,
            BigDecimal amount,
            Long userId
    ) {

        try {

            return withdrawalExecutionService.executeWithdrawal(
                    accountNumber,
                    amount,
                    userId
            );

        } catch (InsufficientFundsException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    TransactionType.WITHDRAW,
                    FailureReason.INSUFFICIENT_FUNDS
            );

            throw ex;

        } catch (DailyWithdrawalLimitExceededException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    TransactionType.WITHDRAW,
                    DAILY_LIMIT_EXCEEDED
            );

            throw ex;

        } catch (AccountSuspendedException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    TransactionType.WITHDRAW,
                    FailureReason.ACCOUNT_SUSPENDED
            );

            throw ex;

        } catch (AccountClosedException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    TransactionType.WITHDRAW,
                    FailureReason.ACCOUNT_CLOSED
            );

            throw ex;
        }
    }
    public Transaction deposit(
            String accountNumber,
            BigDecimal amount,
            Long userId
    ) {
        try {
            return depositExecutionService.executeDeposit(userId, accountNumber, amount);

        } catch (AccountSuspendedException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    TransactionType.DEPOSIT,
                    FailureReason.ACCOUNT_SUSPENDED
            );

            throw ex;

        } catch (AccountClosedException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    TransactionType.DEPOSIT,
                    FailureReason.ACCOUNT_CLOSED
            );

            throw ex;
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Transaction transfer(String sourceAccount, String destinationAccount,
                                BigDecimal amount, Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException("Idempotency key must contain 1 to 128 characters");
        }
        String hash = idempotencyService.generateRequestHash(sourceAccount, destinationAccount, amount);
        var previous = idempotencyService.findResult(userId, idempotencyKey, hash);
        TransferResult result;
        if (previous.isPresent()) {
            result = previous.get();
        } else {
            try {
                result = transferExecutionService.executeTransfer(userId, sourceAccount.trim(),
                        destinationAccount.trim(), amount, idempotencyKey, hash);
            } catch (RuntimeException technicalFailure) {
                // Read committed state after rollback, including an uncertain commit outcome.
                try {
                    var committed = idempotencyService.findResult(userId, idempotencyKey, hash);
                    if (committed.isEmpty()) {
                        throw technicalFailure;
                    }
                    result = committed.get();
                } catch (RuntimeException recoveryFailure) {
                    if (recoveryFailure != technicalFailure) {
                        recoveryFailure.addSuppressed(technicalFailure);
                    }
                    throw recoveryFailure;
                }
            }
        }
        if (result.isSuccessful()) {
            return result.transaction();
        }
        throw replayFailure(result);
    }

    private RuntimeException replayFailure(TransferResult result) {
        String message = result.transaction().getFailureMessage();
        if (message == null) {
            message = "Previous transfer failed: " + result.failureReason();
        }
        return switch (result.failureReason()) {
            case INSUFFICIENT_FUNDS -> new InsufficientFundsException(message);
            case DAILY_LIMIT_EXCEEDED -> new DailyTransferLimitExceededException(message);
            case ACCOUNT_SUSPENDED -> new AccountSuspendedException(message);
            case ACCOUNT_CLOSED -> new AccountClosedException(message);
            case INVALID_TRANSFER -> new BusinessException(message);
        };
    }
}
