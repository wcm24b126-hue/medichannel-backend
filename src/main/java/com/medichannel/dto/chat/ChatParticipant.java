package com.medichannel.dto.chat;

/**
 * Server-derived identity of a chat participant. Used for typing
 * indicators so the client can never spoof who is typing.
 */
public record ChatParticipant(
        String role,
        String name
) {
}
