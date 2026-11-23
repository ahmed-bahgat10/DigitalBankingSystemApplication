package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupService {

    private final IdempotencyRecordRepository repository;

    @Scheduled(fixedDelay = 86400000)
    @Transactional
    public void cleanupExpiredRecords() {

        int deleted = repository.deleteExpiredRecords(
                Instant.now()
        );

        if (deleted > 0) {
            log.info("Deleted {} expired idempotency records", deleted);
        }
    }
}
