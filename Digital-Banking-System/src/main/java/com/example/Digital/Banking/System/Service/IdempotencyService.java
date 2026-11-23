package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.TransferResult;
import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import com.example.Digital.Banking.System.Exception.BusinessException;
import com.example.Digital.Banking.System.Repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TransferResult> findResult(Long userId, String key, String hash) {
        return idempotencyRecordRepository
                .findWithTransactionByUserIdAndIdempotencyKey(userId, key)
                .map(record -> {
                    validateRequestHash(record, hash);
                    if (record.getTransaction() == null) {
                        throw new IllegalStateException("Idempotency result has no transaction");
                    }
                    if (record.getStatus() == IdempotencyStatus.FAILED
                            && record.getFailureReason() == null) {
                        throw new IllegalStateException("Failed idempotency result has no failure reason");
                    }
                    return record.getStatus() == IdempotencyStatus.COMPLETED
                            ? TransferResult.completed(record.getTransaction())
                            : TransferResult.failed(record.getTransaction(), record.getFailureReason());
                });
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
