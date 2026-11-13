package com.medichannel.dto.notice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NoticeRequest(

        @NotBlank(message = "A notice title is required.")
        @Size(max = 150, message = "The title must not exceed 150 characters.")
        String title,

        @NotBlank(message = "A notice body is required.")
        @Size(max = 4000, message = "The body must not exceed 4000 characters.")
        String body,

        boolean published

) {
}
