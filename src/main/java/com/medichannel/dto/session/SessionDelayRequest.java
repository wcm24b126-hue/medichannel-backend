package com.medichannel.dto.session;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SessionDelayRequest(

        @NotNull(message = "Delay is required.")
        @Min(value = 0, message = "Delay cannot be negative.")
        Integer delayMinutes

) {
}
