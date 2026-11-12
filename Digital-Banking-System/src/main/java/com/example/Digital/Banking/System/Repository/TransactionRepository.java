package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface TransactionRepository extends JpaRepository<Transaction,Long> {
    Transaction findByReference(String reference);
    Transaction findBySourceAccountId(long sourceAccountId);
    Transaction findByDestinationAccountId(long destinationAccountId);
    @Query("""
    SELECT COALESCE(SUM(t.amount), 0)
    FROM Transaction t
    WHERE t.sourceAccount.id = :accountId
      AND t.type = com.example.Digital.Banking.System.Enum.TransactionType.WITHDRAW
      AND t.status = com.example.Digital.Banking.System.Enum.TransactionStatus.COMPLETED
      AND t.createdAt >= :startOfDay
      AND t.createdAt < :endOfDay
    """)
    BigDecimal getDailyWithdrawalTotal(
            @Param("accountId") Long accountId,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay
    );

}
