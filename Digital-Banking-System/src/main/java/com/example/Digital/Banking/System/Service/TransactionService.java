package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Entity.WithdrawalProperties;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Exception.AccountClosedException;
import com.example.Digital.Banking.System.Exception.AccountSuspendedException;
import com.example.Digital.Banking.System.Exception.DailyWithdrawalLimitExceededException;
import com.example.Digital.Banking.System.Exception.InsufficientFundsException;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {
    private final FailedTransactionService FailedTransactionService;
    private final WithdrawalExecutionService withdrawalExecutionService;
    private void recordFailureSafely(
            String accountNumber,
            BigDecimal amount,
            Long userId,
            FailureReason failureReason
    ) {
        try {
            FailedTransactionService.recordFailure(
                    accountNumber,
                    amount,
                    userId,
                    failureReason
            );
        } catch (Exception ex) {
            log.error(
                    "Failed to record withdrawal failure. Reason: {}",
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
                    FailureReason.INSUFFICIENT_FUNDS
            );

            throw ex;

        } catch (DailyWithdrawalLimitExceededException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    FailureReason.DAILY_LIMIT_EXCEEDED
            );

            throw ex;

        } catch (AccountSuspendedException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    FailureReason.ACCOUNT_SUSPENDED
            );

            throw ex;

        } catch (AccountClosedException ex) {

            recordFailureSafely(
                    accountNumber,
                    amount,
                    userId,
                    FailureReason.ACCOUNT_CLOSED
            );

            throw ex;
        }
    }
}
