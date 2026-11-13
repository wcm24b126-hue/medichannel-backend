package com.medichannel.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.audit.AuditLogResponse;
import com.medichannel.entity.AuditLog;
import com.medichannel.repository.AuditLogRepository;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider
    ) {
        this.auditLogRepository = auditLogRepository;
        this.messagingTemplateProvider = messagingTemplateProvider;
    }

    @Transactional
    public AuditLog log(String userEmail, String userRole, String action, String details, String ipAddress) {
        AuditLog auditLog = new AuditLog(userEmail, userRole, action, details, ipAddress);
        AuditLog saved = auditLogRepository.save(auditLog);

        // Broadcast real-time audit log event to administrators
        try {
            SimpMessagingTemplate template = messagingTemplateProvider.getIfAvailable();
            if (template != null) {
                AuditLogResponse response = toResponse(saved);
                template.convertAndSend("/topic/audit-logs", response);
            }
        } catch (Exception e) {
            log.warn("Failed to broadcast audit log event via WebSocket: {}", e.getMessage());
        }

        return saved;
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
