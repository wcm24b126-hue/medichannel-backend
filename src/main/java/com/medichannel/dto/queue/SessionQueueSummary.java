package com.medichannel.dto.queue;

import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.SessionStatus;

/**
 * Non-identifying queue state that is safe to broadcast to every
 * participant of a session. It never contains patient names or
 * appointment identifiers.
 */
public record SessionQueueSummary(

        UUID sessionId,

        Integer nowServing,
        Integer nextNumber,

        int remainingAppointments,
        int maxAppointments,
        int delayMinutes,

        SessionStatus status,

        Instant updatedAt

) {
}
