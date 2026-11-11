package com.medichannel.dto.room;

import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.RoomType;

public record RoomResponse(

        UUID id,
        String roomNumber,
        String name,
        String location,
        RoomType roomType,
        int capacity,
        boolean active,
        Instant createdAt,
        Instant updatedAt

) {
}
