package com.medichannel.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.AuditLog;

/**
 * Read/append repository for the append-only audit log.
 *
 * Audit rows must never be modified or removed; V19 installs database
 * triggers that reject UPDATE, DELETE and TRUNCATE on audit_logs.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findTop100ByOrderByCreatedAtDesc();

    List<AuditLog> findTop100ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<AuditLog> findTop100ByAppointmentIdOrderByCreatedAtDesc(UUID appointmentId);

    Page<AuditLog> findByUserId(UUID userId, Pageable pageable);

    Page<AuditLog> findByAppointmentId(UUID appointmentId, Pageable pageable);

}
