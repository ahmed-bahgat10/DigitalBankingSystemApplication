package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.IdempotencyProperties;
import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Entity.User;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import com.example.Digital.Banking.System.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class IdempotencyReservationService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final UserRepository userRepository;
    private final IdempotencyProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord reserve(
            Long userId,
            String idempotencyKey,
            String requestHash
    ) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Instant now = Instant.now();

        IdempotencyRecord record = new IdempotencyRecord();

        record.setUser(user);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setStatus(IdempotencyStatus.IN_PROGRESS);
        record.setCreatedAt(now);
        record.setExpiresAt(
                now.plus(properties.getRetentionHours(), ChronoUnit.HOURS)
        );
        record.setProcessingExpiresAt(
                now.plus(properties.getProcessingTimeoutMinutes(), ChronoUnit.MINUTES)
        );

        return idempotencyRecordRepository.saveAndFlush(record);
    }
}
