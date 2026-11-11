package com.medichannel.dto.notification;

import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.NotificationType;

public record NotificationResponse(

        UUID notificationId,
        NotificationType type,
        String title,
        String message,
        UUID appointmentId,
        boolean read,
        Instant readAt,
        Instant createdAt

) {
}
