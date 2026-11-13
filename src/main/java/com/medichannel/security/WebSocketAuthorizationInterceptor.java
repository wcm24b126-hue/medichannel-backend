package com.medichannel.security;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.messaging.support.ChannelInterceptor;

import com.medichannel.service.ConsultationAccessService;

/**
 * Denies every STOMP destination by default and only permits the
 * destinations the application actually uses.
 */
@Component
public class WebSocketAuthorizationInterceptor
        implements ChannelInterceptor {

    private static final String UUID_PART = "([0-9a-fA-F-]{36})";

    private static final Pattern CHAT_SUBSCRIPTION = Pattern.compile(
            "^/topic/appointments/" + UUID_PART + "/chat$"
    );

    private static final Pattern CHAT_SEND = Pattern.compile(
            "^/app/appointments/" + UUID_PART + "/chat$"
    );

    private static final Pattern TYPING_SUBSCRIPTION = Pattern.compile(
            "^/topic/appointments/" + UUID_PART + "/typing$"
    );

    private static final Pattern TYPING_SEND = Pattern.compile(
            "^/app/appointments/" + UUID_PART + "/typing$"
    );

    private static final Pattern STATUS_SUBSCRIPTION = Pattern.compile(
            "^/topic/appointments/" + UUID_PART + "/status$"
    );

    private static final Pattern SESSION_QUEUE_SUBSCRIPTION = Pattern.compile(
            "^/topic/sessions/" + UUID_PART + "/queue$"
    );

    // Private per-user queues. Spring resolves /user/... to the
    // authenticated session, so one user can never read another
    // user's queue.
    private static final Set<String> ALLOWED_USER_SUBSCRIPTIONS = Set.of(
            "/user/queue/notifications",
            "/user/queue/errors"
    );

    // Non-identifying session capacity updates.
    private static final Set<String> ALLOWED_AUTHENTICATED_SUBSCRIPTIONS =
            Set.of("/topic/sessions");

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

        if (command == StompCommand.SUBSCRIBE) {
            if ("/topic/audit-logs".equals(destination)) {
                requireRole(accessor, message, "ADMIN");
                return message;
            }

            if (ALLOWED_USER_SUBSCRIPTIONS.contains(destination)) {
                requireAuthenticated(accessor, message);
                return message;
            }

            if (ALLOWED_AUTHENTICATED_SUBSCRIPTIONS.contains(destination)
                    || SESSION_QUEUE_SUBSCRIPTION.matcher(destination)
                            .matches()) {
                requireAuthenticated(accessor, message);
                return message;
            }
        }

        Pattern expectedPattern;

        if (command == StompCommand.SUBSCRIBE) {
            expectedPattern = matchSubscriptionPattern(destination);
        } else {
            expectedPattern = matchSendPattern(destination);
        }

        if (expectedPattern == null) {
            throw new MessageDeliveryException(
                    message,
                    "Access to this WebSocket destination is not permitted."
            );
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

        Authentication authentication = accessor.getUser()
                instanceof Authentication auth
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

    private Pattern matchSubscriptionPattern(String destination) {
        if (CHAT_SUBSCRIPTION.matcher(destination).matches()) {
            return CHAT_SUBSCRIPTION;
        }
        if (TYPING_SUBSCRIPTION.matcher(destination).matches()) {
            return TYPING_SUBSCRIPTION;
        }
        if (STATUS_SUBSCRIPTION.matcher(destination).matches()) {
            return STATUS_SUBSCRIPTION;
        }
        return null;
    }

    private Pattern matchSendPattern(String destination) {
        if (CHAT_SEND.matcher(destination).matches()) {
            return CHAT_SEND;
        }
        if (TYPING_SEND.matcher(destination).matches()) {
            return TYPING_SEND;
        }
        return null;
    }

    private void requireAuthenticated(
            StompHeaderAccessor accessor,
            Message<?> message
    ) {
        if (accessor.getUser() == null) {
            throw new MessageDeliveryException(
                    message,
                    "WebSocket authentication is required."
            );
        }
    }

    private void requireRole(
            StompHeaderAccessor accessor,
            Message<?> message,
            String... allowedRoles
    ) {
        requireAuthenticated(accessor, message);
        if (accessor.getUser() instanceof Authentication auth) {
            for (String role : allowedRoles) {
                if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role))) {
                    return;
                }
            }
        }
        throw new MessageDeliveryException(
                message,
                "You do not have permission to subscribe to this topic."
        );
    }
}
