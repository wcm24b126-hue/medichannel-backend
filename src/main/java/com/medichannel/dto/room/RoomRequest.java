package com.medichannel.dto.room;

import com.medichannel.enums.RoomType;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RoomRequest(

        @NotBlank
        @Size(max = 30)
        String roomNumber,

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 255)
        String location,

        @NotNull
        RoomType roomType,

        @Min(1)
        int capacity

) {
}
