package com.medichannel.dto.chat;

import java.util.UUID;

/**
 * Ephemeral typing event. It is broadcast only, never stored.
 */
public record TypingIndicator(

        UUID appointmentId,
        String senderRole,
        String senderName,
        boolean typing

) {
}
