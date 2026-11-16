package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import com.example.Digital.Banking.System.Exception.BusinessException;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferFailureFinalizationService {

    private final IdempotencyRecordRepository idempotencyRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeFailure(
            Long idempotencyRecordId,
            Long userId,
            String sourceAccountNumber,
            String destinationAccountNumber,
            java.math.BigDecimal amount,
            FailureReason failureReason
    ) {

        IdempotencyRecord record = idempotencyRepository
                .findByIdForUpdate(idempotencyRecordId)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record not found"
                ));

        if (record.getStatus() != IdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Idempotency record is not in progress"
            );
        }

        Account source = accountRepository
                .findByUserIdAndAccountNumber(userId, sourceAccountNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "Source account not found"
                ));

        Account destination = accountRepository
                .findByAccountNumber(destinationAccountNumber);

        if (destination == null) {
            throw new IllegalStateException(
                    "Destination account not found"
            );
        }

        Transaction failedTransaction = new Transaction();

        failedTransaction.setReference(UUID.randomUUID().toString());
        failedTransaction.setAmount(amount);
        failedTransaction.setSourceAccount(source);
        failedTransaction.setDestinationAccount(destination);
        failedTransaction.setType(TransactionType.TRANSFER);
        failedTransaction.setStatus(TransactionStatus.FAILED);
        failedTransaction.setFailureReason(failureReason);

        Transaction savedTransaction =
                transactionRepository.save(failedTransaction);

        record.setTransaction(savedTransaction);
        record.setFailureReason(failureReason);
        record.setStatus(IdempotencyStatus.FAILED);
    }
}
