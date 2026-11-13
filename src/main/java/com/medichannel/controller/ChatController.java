package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.chat.ChatMessagePayload;
import com.medichannel.dto.chat.ChatMessageResponse;
import com.medichannel.service.ChatService;

@RestController
@RequestMapping("/api/v1/appointments")
public class ChatController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(
            ChatService chatService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
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

    // Send chat message (REST fallback + STOMP broadcast)
    @PostMapping("/{appointmentId}/chat/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody ChatMessagePayload payload
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        ChatMessageResponse response = chatService.sendMessage(
                appointmentId,
                userId,
                payload.content()
        );

        messagingTemplate.convertAndSend(
                "/topic/appointments/" + appointmentId + "/chat",
                response
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
