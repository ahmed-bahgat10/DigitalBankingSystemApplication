package com.example.Digital.Banking.System.Entity;

import com.example.Digital.Banking.System.Enum.FailureReason;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(unique = true, nullable = false)
    private String reference = UUID.randomUUID().toString();

    @Column(nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private Account destinationAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at", nullable = true)
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    private FailureReason failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

}
