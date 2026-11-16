package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyStateService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    // Called inside the same transaction as the transfer.
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCompleted(Long idempotencyRecordId, Transaction transaction) {

        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(idempotencyRecordId)
                .orElseThrow(() ->
                        new IllegalStateException("Idempotency record not found")
                );

        if (record.getStatus() != IdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Idempotency record is not IN_PROGRESS"
            );
        }

        record.setTransaction(transaction);
        record.setFailureReason(null);
        record.setStatus(IdempotencyStatus.COMPLETED);
    }

    // Called after the transfer transaction has rolled back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(
            Long idempotencyRecordId,
            FailureReason failureReason,
            Transaction failedTransaction
    ) {

        if (failureReason == null) {
            throw new IllegalArgumentException("Failure reason is required");
        }

        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(idempotencyRecordId)
                .orElseThrow(() ->
                        new IllegalStateException("Idempotency record not found")
                );

        if (record.getStatus() != IdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Idempotency record is not IN_PROGRESS"
            );
        }

        record.setTransaction(failedTransaction);
        record.setFailureReason(failureReason);
        record.setStatus(IdempotencyStatus.FAILED);
    }

    // Allows retry with the same key after a technical failure.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseForRetry(Long idempotencyRecordId) {

        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(idempotencyRecordId)
                .orElse(null);

        if (record == null) {
            return;
        }

        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            idempotencyRecordRepository.delete(record);
        }
    }
}
