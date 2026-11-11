package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.chat.ChatMessageResponse;
import com.medichannel.service.ChatService;

@RestController
@RequestMapping("/api/v1/appointments")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // Persistent chat history for the patient and the assigned doctor.
    @GetMapping("/{appointmentId}/chat/messages")
    public ResponseEntity<List<ChatMessageResponse>> getHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            @RequestParam(required = false) Integer limit
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                chatService.getHistory(appointmentId, userId, limit)
        );
    }
}
