package com.medichannel.dto.chat;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(

        UUID messageId,
        UUID appointmentId,
        UUID senderUserId,
        String senderRole,
        String senderName,
        String content,
        Instant sentAt

) {
}
