package com.medichannel.controller;

import java.security.Principal;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.medichannel.dto.chat.ChatErrorResponse;
import com.medichannel.dto.chat.ChatMessagePayload;
import com.medichannel.dto.chat.ChatMessageResponse;
import com.medichannel.dto.chat.ChatParticipant;
import com.medichannel.dto.chat.TypingIndicator;
import com.medichannel.dto.chat.TypingPayload;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.exception.TooManyRequestsException;
import com.medichannel.service.ChatService;
import com.medichannel.service.RateLimitingService;

@Controller
public class ChatWebSocketController {

    private static final String TOPIC_PREFIX = "/topic/appointments/";
    private static final String TOPIC_SUFFIX = "/chat";
    private static final String TYPING_TOPIC_SUFFIX = "/typing";
    private static final String ERROR_QUEUE = "/queue/errors";

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RateLimitingService rateLimitingService;

    public ChatWebSocketController(
            ChatService chatService,
            SimpMessagingTemplate messagingTemplate,
            RateLimitingService rateLimitingService
    ) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
        this.rateLimitingService = rateLimitingService;
    }

    @MessageMapping("/appointments/{appointmentId}/chat")
    public void sendMessage(
            @DestinationVariable UUID appointmentId,
            @Payload @Valid ChatMessagePayload payload,
            Principal principal
    ) {
        if (principal == null) {
            throw new ForbiddenOperationException(
                    "WebSocket authentication is required."
            );
        }

        UUID senderUserId = UUID.fromString(principal.getName());

        // Enforce per-account chat rate limiting (30 messages per minute)
        rateLimitingService.checkRateLimit("ws:chat:" + senderUserId, 30, "chat messages");

        ChatMessageResponse response = chatService.sendMessage(
                appointmentId,
                senderUserId,
                payload.content()
        );

        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + appointmentId + TOPIC_SUFFIX,
                response
        );
    }

    @MessageMapping("/appointments/{appointmentId}/typing")
    public void sendTypingIndicator(
            @DestinationVariable UUID appointmentId,
            @Payload @Valid TypingPayload payload,
            Principal principal
    ) {
        if (principal == null) {
            throw new ForbiddenOperationException(
                    "WebSocket authentication is required."
            );
        }

        UUID senderUserId = UUID.fromString(principal.getName());

        // Enforce typing rate limiting (20 events per minute) to prevent event flooding
        if (!rateLimitingService.tryAcquire("ws:typing:" + senderUserId, 20)) {
            return;
        }

        ChatParticipant participant = chatService.describeSender(
                appointmentId,
                senderUserId
        );

        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + appointmentId + TYPING_TOPIC_SUFFIX,
                new TypingIndicator(
                        appointmentId,
                        participant.role(),
                        participant.name(),
                        Boolean.TRUE.equals(payload.typing())
                )
        );
    }

    @MessageExceptionHandler
    public void handleException(
            Exception exception,
            Principal principal
    ) {
        if (principal == null) {
            return;
        }

        String code = "CHAT_ERROR";
        String message = "The message could not be sent.";

        if (exception instanceof ForbiddenOperationException
                || exception instanceof ResourceNotFoundException) {
            code = "FORBIDDEN";
            message = "You are not authorized to use this conversation.";
        } else if (exception instanceof ConflictException) {
            code = "CONFLICT";
            message = exception.getMessage();
        } else if (exception instanceof IllegalArgumentException) {
            code = "INVALID_MESSAGE";
            message = exception.getMessage();
        } else if (exception instanceof TooManyRequestsException) {
            code = "RATE_LIMITED";
            message = exception.getMessage();
        }

        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                ERROR_QUEUE,
                new ChatErrorResponse(code, message)
        );
    }
}
