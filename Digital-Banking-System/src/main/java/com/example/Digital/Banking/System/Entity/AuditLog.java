package com.example.Digital.Banking.System.Entity;

import com.example.Digital.Banking.System.Enum.AduitAction;
import com.example.Digital.Banking.System.Enum.AuditEntityType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AduitAction  action;

    @Column(nullable = false)
    private long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuditEntityType entityType;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Lob
    private String metadata;
}
