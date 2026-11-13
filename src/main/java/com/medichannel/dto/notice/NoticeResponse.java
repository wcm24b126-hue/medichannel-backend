package com.medichannel.dto.notice;

import java.time.Instant;
import java.util.UUID;

public record NoticeResponse(

        UUID id,
        String title,
        String body,
        boolean published,
        Instant publishedAt,
        String createdByName,
        Instant createdAt,
        Instant updatedAt

) {
}
