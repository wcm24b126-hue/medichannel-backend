package com.medichannel.security;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.medichannel.service.ConsultationAccessService;

@Component
public class WebSocketAuthorizationInterceptor
        implements ChannelInterceptor {

    private static final Pattern CHAT_SUBSCRIPTION =
            Pattern.compile(
                    "^/topic/appointments/([0-9a-fA-F-]{36})/chat$"
            );

    private static final Pattern CHAT_SEND =
            Pattern.compile(
                    "^/app/appointments/([0-9a-fA-F-]{36})/chat$"
            );

    private final ConsultationAccessService accessService;

    public WebSocketAuthorizationInterceptor(
            ConsultationAccessService accessService
    ) {
        this.accessService = accessService;
    }

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        message,
                        StompHeaderAccessor.class
                );

        if (accessor == null) {
            throw new MessageDeliveryException(
                    message,
                    "Invalid WebSocket message."
            );
        }

        StompCommand command = accessor.getCommand();

        if (command != StompCommand.SUBSCRIBE
                && command != StompCommand.SEND) {
            return message;
        }

        String destination = accessor.getDestination();

        if (destination == null) {
            throw new MessageDeliveryException(
                    message,
                    "WebSocket destination is required."
            );
        }

        Pattern expectedPattern;

        if (command == StompCommand.SUBSCRIBE) {
            expectedPattern = CHAT_SUBSCRIPTION;
        } else {
            expectedPattern = CHAT_SEND;
        }

        Matcher matcher = expectedPattern.matcher(destination);

        if (!matcher.matches()) {
            throw new MessageDeliveryException(
                    message,
                    "Access to this WebSocket destination is not permitted."
            );
        }

        UUID appointmentId;

        try {
            appointmentId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            throw new MessageDeliveryException(
                    message,
                    "Invalid appointment identifier."
            );
        }

        Authentication authentication = accessor.getUser() instanceof Authentication auth
                ? auth
                : null;

        if (!accessService.canAccess(appointmentId, authentication)) {
            throw new MessageDeliveryException(
                    message,
                    "You are not authorized to access this consultation."
            );
        }

        return message;
    }
}
