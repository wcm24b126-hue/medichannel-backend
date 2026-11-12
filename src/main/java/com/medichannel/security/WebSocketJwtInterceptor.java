package com.medichannel.security;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.medichannel.entity.User;
import com.medichannel.repository.UserRepository;

@Component
public class WebSocketJwtInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final UserRepository userRepository;

    public WebSocketJwtInterceptor(
            @Qualifier("webSocketJwtDecoder") JwtDecoder jwtDecoder,
            UserRepository userRepository
    ) {
        this.jwtDecoder = jwtDecoder;
        this.userRepository = userRepository;
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

        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String authorization =
                accessor.getFirstNativeHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new MessageDeliveryException(
                    message,
                    "WebSocket authentication is required."
            );
        }

        String token = authorization.substring(7).trim();

        try {
            Jwt jwt = jwtDecoder.decode(token);

            UUID userId = UUID.fromString(jwt.getSubject());

            User user = userRepository.findByIdWithRole(userId)
                    .orElseThrow(() -> new MessageDeliveryException(
                            message,
                            "WebSocket authentication failed."
                    ));

            if (!"ACTIVE".equals(user.getAccountStatus())
                    || !user.isEmailVerified()) {
                throw new MessageDeliveryException(
                        message,
                        "This account is not permitted to connect."
                );
            }

            String currentRole = user.getRole().getName();

            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(
                            user.getId().toString(),
                            null,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            "ROLE_" + currentRole
                                    )
                            )
                    );

            accessor.setUser(authentication);

            return message;

        } catch (JwtException | IllegalArgumentException exception) {
            throw new MessageDeliveryException(
                    message,
                    "WebSocket authentication failed."
            );
        }
    }
}
