package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.DepositProperties;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import com.example.Digital.Banking.System.Exception.AccountClosedException;
import com.example.Digital.Banking.System.Exception.AccountSuspendedException;
import com.example.Digital.Banking.System.Exception.InvalidAmountException;
import com.example.Digital.Banking.System.Exception.NotFoundException;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.security.auth.login.AccountNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor

public class DepositExecutionService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final DepositProperties depositProperties;

    @Transactional
    public Transaction  executeDeposit(
            Long userId,
            String accountNumber,
            BigDecimal amount
    ) {
        if (amount == null) {
            throw new InvalidAmountException("Invalid amount");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Invalid amount");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidAmountException("Invalid amount");
        }

        if (amount.compareTo(depositProperties.getMinAmount()) < 0 ||
                amount.compareTo(depositProperties.getMaxAmount()) > 0) {

            throw new InvalidAmountException("Invalid amount");
        }
        Account account = accountRepository
                .findByUserIdAndAccountNumberForUpdate(userId, accountNumber)
                .orElseThrow(() -> new NotFoundException("Account not found"));

        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException("Account suspended");
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException("Account closed");
        }

        account.setBalance(account.getBalance().add(amount));
        Transaction transaction = new Transaction();
        transaction.setReference(UUID.randomUUID().toString());
        transaction.setSourceAccount(null);
        transaction.setDestinationAccount(account);
        transaction.setType(TransactionType.DEPOSIT);
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setAmount(amount);
        transaction.setFailureReason(null);

        return transactionRepository.save(transaction);


    }
}
