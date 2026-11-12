package com.example.Digital.Banking.System.Entity;

import com.example.Digital.Banking.System.Enum.CancellationReason;
import com.example.Digital.Banking.System.Enum.ScheduledTransferStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "scheduled_transfer")
@Getter
@Setter
@NoArgsConstructor
public class ScheduledTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private Account destinationAccount;

    @Column(nullable = false)
    private BigDecimal amount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduledTransferStatus status;

    @Column(name = "executed_at", nullable = true)
    private Instant executedAt;

    private LocalDateTime cancelledAt;

    @Enumerated(EnumType.STRING)
    private CancellationReason cancellationReason;

}
