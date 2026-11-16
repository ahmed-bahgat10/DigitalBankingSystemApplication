package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import com.example.Digital.Banking.System.Enum.IdempotencyStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT i FROM IdempotencyRecord i
        WHERE i.user.id = :userId
        AND i.idempotencyKey = :idempotencyKey
    """)
    Optional<IdempotencyRecord> findByUserIdAndKeyForUpdate(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(
            Long userId,
            String idempotencyKey
    );
    @EntityGraph(attributePaths = {"transaction"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT i FROM IdempotencyRecord i
    WHERE i.id = :id
""")
    Optional<IdempotencyRecord> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"transaction"})
    Optional<IdempotencyRecord> findWithTransactionByUserIdAndIdempotencyKey(
            Long userId,
            String idempotencyKey
    );

    @Modifying
    @Query("""
       DELETE FROM IdempotencyRecord i
       WHERE i.expiresAt <= :now
       AND i.status <> :inProgress
       """)
    int deleteExpiredRecords(
            @Param("now") Instant now,
            @Param("inProgress") IdempotencyStatus inProgress
    );
}