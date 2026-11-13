package com.medichannel.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.audit.AuditLogResponse;
import com.medichannel.entity.AuditLog;
import com.medichannel.repository.AuditLogRepository;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public AuditLog log(String userEmail, String userRole, String action, String details, String ipAddress) {
        AuditLog auditLog = new AuditLog(userEmail, userRole, action, details, ipAddress);
        return auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getRecentAuditLogs() {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getCreatedAt(),
                log.getUserEmail(),
                log.getUserRole(),
                log.getAction(),
                log.getDetails(),
                log.getIpAddress()
        );
    }
}
