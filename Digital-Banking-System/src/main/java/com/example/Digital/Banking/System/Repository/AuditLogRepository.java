package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog,Long> {
}
