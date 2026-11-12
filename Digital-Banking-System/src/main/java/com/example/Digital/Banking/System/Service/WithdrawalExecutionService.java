package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Entity.WithdrawalProperties;
import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import com.example.Digital.Banking.System.Exception.*;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WithdrawalExecutionService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final WithdrawalProperties withdrawalProperties;

    @Transactional
    public Transaction executeWithdrawal(
            String accountNumber,
            BigDecimal amount,
            Long userId
    ) {
        if (amount == null ||
                amount.compareTo(BigDecimal.ZERO) <= 0 ||
                amount.stripTrailingZeros().scale() > 2 ||
                amount.compareTo(withdrawalProperties.getMinAmount()) < 0 ||
                amount.compareTo(withdrawalProperties.getMaxAmount()) > 0) {

            throw new BusinessException("Invalid withdrawal amount");
        }
        Account account = accountRepository
                .findByUserIdAndAccountNumberForUpdate(userId, accountNumber)
                .orElseThrow(() -> new NotFoundException("Account not found"));

        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException("Account is suspended");
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException("Account is closed");
        }
        LocalDate today = LocalDate.now();

        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();

        BigDecimal dailyTotal = transactionRepository.getDailyWithdrawalTotal(
                account.getId(),
                startOfDay,
                endOfDay
        );
        if (dailyTotal.add(amount)
                .compareTo(withdrawalProperties.getDailyLimit()) > 0) {

            throw new DailyWithdrawalLimitExceededException(
                    "Daily withdrawal limit exceeded"
            );
        }
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient balance");
        }
        account.setBalance(account.getBalance().subtract(amount));

        Transaction transaction = new Transaction();

        transaction.setReference(UUID.randomUUID().toString());
        transaction.setAmount(amount);
        transaction.setSourceAccount(account);
        transaction.setDestinationAccount(null);
        transaction.setType(TransactionType.WITHDRAW);
        transaction.setStatus(TransactionStatus.COMPLETED);

        return transactionRepository.save(transaction);
    }
}
