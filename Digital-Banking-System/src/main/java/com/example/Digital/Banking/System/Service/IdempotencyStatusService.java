package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.IdempotencyResult;
import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyStatusService {

    private final IdempotencyRecordRepository repository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true
    )
    public IdempotencyResult readLatest(Long userId, String idempotencyKey) {

        IdempotencyRecord record = repository
                .findWithTransactionByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record not found"
                ));

        return new IdempotencyResult(
                record.getStatus(),
                record.getTransaction(),
                record.getFailureReason()
        );
    }
}
