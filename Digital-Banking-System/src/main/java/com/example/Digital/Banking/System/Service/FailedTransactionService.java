package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import com.example.Digital.Banking.System.Exception.BusinessException;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FailedTransactionService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            String accountNumber,
            BigDecimal amount,
            Long userId,
            TransactionType transactionType,
            FailureReason failureReason
    ) {

        if (transactionType == null ||
                (transactionType != TransactionType.DEPOSIT &&
                        transactionType != TransactionType.WITHDRAW)) {
            throw new BusinessException("Unsupported transaction type");
        }
        if (failureReason == null) {
            throw new BusinessException("Failure reason is required");
        }
        Account account = accountRepository
                .findByUserIdAndAccountNumber(userId, accountNumber)
                .orElseThrow(() -> new BusinessException("Account not found"));

        Transaction transaction = new Transaction();

        transaction.setReference(UUID.randomUUID().toString());
        transaction.setAmount(amount);

        if (transactionType == TransactionType.DEPOSIT) {
            transaction.setSourceAccount(null);
            transaction.setDestinationAccount(account);
        } else {
            transaction.setSourceAccount(account);
            transaction.setDestinationAccount(null);
        }

        transaction.setType(transactionType);
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureReason(failureReason);

        transactionRepository.save(transaction);
    }
}
