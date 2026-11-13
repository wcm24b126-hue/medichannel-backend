package com.medichannel.dto.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        Instant createdAt,
        String userEmail,
        String userRole,
        String action,
        String details,
        String ipAddress
) {
}
