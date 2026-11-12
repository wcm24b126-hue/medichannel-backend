package com.medichannel.dto.auth;

/**
 * Short lived, WebSocket-only credential. It cannot be replayed
 * against the REST API.
 */
public record WebSocketTicketResponse(

        String ticket,

        long expiresIn

) {
}
