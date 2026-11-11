package com.medichannel.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessagePayload(

        @NotBlank(message = "Message content is required.")
        @Size(max = 2000, message = "Message must not exceed 2000 characters.")
        String content

) {
}
