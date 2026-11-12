package com.medichannel.dto.chat;

import jakarta.validation.constraints.NotNull;

/**
 * Client supplied typing state. The sender identity is never taken
 * from the client; it is derived from the authenticated principal.
 */
public record TypingPayload(

        @NotNull(message = "Typing state is required.")
        Boolean typing

) {
}
