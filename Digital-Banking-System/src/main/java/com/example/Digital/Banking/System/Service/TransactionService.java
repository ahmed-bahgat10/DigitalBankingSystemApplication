package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.IdempotencyReservation;
import com.example.Digital.Banking.System.DTO.IdempotencyResult;
import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Entity.WithdrawalProperties;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import com.example.Digital.Banking.System.Exception.*;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
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
    private final IdempotencyStateService idempotencyStateService;
    private final TransferFailureFinalizationService transferFailureFinalizationService;

    private void recordTransferFailureSafely(
            String sourceAccountNumber,
            String destinationAccountNumber,
            BigDecimal amount,
            Long userId,
            FailureReason failureReason,
            Long idempotencyRecordId
    ) {
        try {
            transferFailureFinalizationService.finalizeFailure(
                    idempotencyRecordId,
                    userId,
                    sourceAccountNumber,
                    destinationAccountNumber,
                    amount,
                    failureReason
            );
        } catch (Exception ex) {
            log.error(
                    "Failed to finalize transfer failure. Idempotency ID: {}, Reason: {}",
                    idempotencyRecordId,
                    failureReason,
                    ex
            );
        }
    }

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

    public Transaction transfer(
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            Long userId,
            String idempotencyKey
    ) {

        if (sourceAccount == null || sourceAccount.isBlank()
                || destinationAccount == null || destinationAccount.isBlank()) {
            throw new IllegalArgumentException("Account numbers are required");
        }

        String normalizedSource = sourceAccount.trim();
        String normalizedDestination = destinationAccount.trim();

        String requestHash = idempotencyService.generateRequestHash(
                normalizedSource,
                normalizedDestination,
                amount
        );

        IdempotencyReservation reservation = idempotencyService.getOrReserve(
                userId,
                idempotencyKey,
                requestHash
        );

        Long recordId = reservation.record().getId();

        // المفتاح مستخدم قبل كده: ننتظر النتيجة أو نعمل Replay.
        if (!reservation.newlyCreated()) {

            IdempotencyRecord previousRecord =
                    idempotencyService.waitForCompletion(userId, idempotencyKey);

            IdempotencyResult previousResult =
                    idempotencyService.getExistingResult(previousRecord);

            return replayTransferResult(previousResult);
        }

        try {

            return transferExecutionService.executeTransfer(
                    userId,
                    normalizedSource,
                    normalizedDestination,
                    amount,
                    recordId
            );

        } catch (InsufficientFundsException exception) {

            finalizeTransferFailureSafely(
                    recordId, userId, normalizedSource, normalizedDestination,
                    amount, FailureReason.INSUFFICIENT_FUNDS
            );

            throw exception;

        } catch (DailyTransferLimitExceededException exception) {

            finalizeTransferFailureSafely(
                    recordId, userId, normalizedSource, normalizedDestination,
                    amount, FailureReason.DAILY_LIMIT_EXCEEDED
            );

            throw exception;

        } catch (AccountSuspendedException exception) {

            finalizeTransferFailureSafely(
                    recordId, userId, normalizedSource, normalizedDestination,
                    amount, FailureReason.ACCOUNT_SUSPENDED
            );

            throw exception;

        } catch (AccountClosedException exception) {

            finalizeTransferFailureSafely(
                    recordId, userId, normalizedSource, normalizedDestination,
                    amount, FailureReason.ACCOUNT_CLOSED
            );

            throw exception;
        }
    }

    private RuntimeException replayFailure(FailureReason reason) {

        if (reason == null) {
            return new BusinessException("Previous transfer failed");
        }

        return switch (reason) {
            case INSUFFICIENT_FUNDS ->
                    new InsufficientFundsException("Insufficient funds");

            case DAILY_LIMIT_EXCEEDED ->
                    new DailyTransferLimitExceededException("Daily limit exceeded");

            case ACCOUNT_SUSPENDED ->
                    new AccountSuspendedException("Account is not available");

            case ACCOUNT_CLOSED ->
                    new AccountClosedException("Account is not available");
        };
    }

    private Transaction replayTransferResult(IdempotencyResult result) {

        if (result.status() == IdempotencyStatus.COMPLETED) {
            return result.transaction();
        }

        if (result.status() == IdempotencyStatus.FAILED) {

            switch (result.failureReason()) {

                case INSUFFICIENT_FUNDS ->
                        throw new InsufficientFundsException("Insufficient funds");

                case DAILY_LIMIT_EXCEEDED ->
                        throw new DailyTransferLimitExceededException(
                                "Daily transfer limit exceeded"
                        );

                case ACCOUNT_SUSPENDED ->
                        throw new AccountSuspendedException("Account is suspended");

                case ACCOUNT_CLOSED ->
                        throw new AccountClosedException("Account is closed");
            }
        }

        throw new IdempotencyRequestInProgressException(
                "The previous request is still being processed"
        );
    }
    private void finalizeTransferFailureSafely(
            Long recordId,
            Long userId,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            FailureReason failureReason
    ) {

        try {

            transferFailureFinalizationService.finalizeFailure(
                    recordId,
                    userId,
                    sourceAccount,
                    destinationAccount,
                    amount,
                    failureReason
            );

        } catch (Exception exception) {

            log.error(
                    "Failed to finalize transfer failure. Idempotency record ID: {}",
                    recordId,
                    exception
            );
        }
    }
}
