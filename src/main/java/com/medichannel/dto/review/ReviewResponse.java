package com.medichannel.dto.review;

import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(

        UUID reviewId,
        UUID appointmentId,
        UUID doctorId,
        int rating,
        String comment,
        Instant createdAt

) {
}
