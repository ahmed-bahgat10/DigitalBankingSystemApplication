package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.IdempotencyReservation;
import com.example.Digital.Banking.System.DTO.IdempotencyResult;
import com.example.Digital.Banking.System.Entity.IdempotencyProperties;
import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import com.example.Digital.Banking.System.Exception.BusinessException;
import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final IdempotencyReservationService reservationService;
    private final IdempotencyProperties properties;
    private final IdempotencyStatusService statusService;

    public IdempotencyRecord waitForCompletion(
            Long userId,
            String idempotencyKey
    ) {

        long deadline = System.nanoTime()
                + properties.getWaitTimeoutSeconds() * 1_000_000_000L;

        while (System.nanoTime() < deadline) {

            IdempotencyResult result = statusService.readLatest(
                    userId,
                    idempotencyKey
            );

            if (result.status() != IdempotencyStatus.IN_PROGRESS) {
                return idempotencyRecordRepository
                        .findWithTransactionByUserIdAndIdempotencyKey(
                                userId,
                                idempotencyKey
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency record not found"
                        ));
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();

                throw new BusinessException(
                        "Interrupted while waiting for transfer result"
                );
            }
        }

        throw new BusinessException(
                "The same request is still being processed. Please retry shortly."
        );
    }

    public IdempotencyReservation getOrReserve(
            Long userId,
            String idempotencyKey,
            String requestHash
    ) {

        var existing = idempotencyRecordRepository
                .findWithTransactionByUserIdAndIdempotencyKey(userId, idempotencyKey);

        if (existing.isPresent()) {
            validateRequestHash(existing.get(), requestHash);

            return new IdempotencyReservation(existing.get(), false);
        }

        try {
            IdempotencyRecord record = reservationService.reserve(
                    userId,
                    idempotencyKey,
                    requestHash
            );

            return new IdempotencyReservation(record, true);

        } catch (DataIntegrityViolationException exception) {

            var concurrentRecord = idempotencyRecordRepository
                    .findWithTransactionByUserIdAndIdempotencyKey(userId, idempotencyKey);

            if (concurrentRecord.isEmpty()) {
                throw exception;
            }

            validateRequestHash(concurrentRecord.get(), requestHash);

            return new IdempotencyReservation(
                    concurrentRecord.get(),
                    false
            );
        }
    }

    public IdempotencyResult getExistingResult(IdempotencyRecord record) {

        return switch (record.getStatus()) {

            case COMPLETED -> new IdempotencyResult(
                    record.getStatus(),
                    record.getTransaction(),
                    null
            );

            case FAILED -> new IdempotencyResult(
                    record.getStatus(),
                    record.getTransaction(),
                    record.getFailureReason()
            );

            case IN_PROGRESS -> null;
        };
    }

    public String generateRequestHash(
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount
    ) {

        if (sourceAccount == null || sourceAccount.isBlank()
                || destinationAccount == null || destinationAccount.isBlank()
                || amount == null) {

            throw new BusinessException(
                    "Transfer details must not be null or blank"
            );
        }
        String normalizedAmount = amount.stripTrailingZeros().toPlainString();

        String requestData = sourceAccount.trim()
                + "|" + destinationAccount.trim()
                + "|" + normalizedAmount;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    requestData.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }



    public void validateRequestHash(
            IdempotencyRecord record,
            String requestHash
    ) {

        if (!record.getRequestHash().equals(requestHash)) {
            throw new BusinessException(
                    "Idempotency key was already used with different request data"
            );
        }
    }
}
