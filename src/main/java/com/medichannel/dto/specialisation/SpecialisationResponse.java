package com.medichannel.dto.specialisation;

import java.time.Instant;
import java.util.UUID;

public record SpecialisationResponse(

        UUID id,
        String name,
        String description,
        boolean active,
        Instant createdAt

) {
}
