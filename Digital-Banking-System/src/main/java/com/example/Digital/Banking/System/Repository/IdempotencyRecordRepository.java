package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    @EntityGraph(attributePaths = {"transaction"})
    Optional<IdempotencyRecord> findWithTransactionByUserIdAndIdempotencyKey(
            Long userId, String idempotencyKey);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord i WHERE i.expiresAt <= :now")
    int deleteExpiredRecords(@Param("now") Instant now);
}
