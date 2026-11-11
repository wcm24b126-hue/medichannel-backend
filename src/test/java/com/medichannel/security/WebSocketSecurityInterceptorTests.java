package com.medichannel.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.medichannel.entity.Role;
import com.medichannel.entity.User;
import com.medichannel.repository.UserRepository;
import com.medichannel.service.ConsultationAccessService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketSecurityInterceptorTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID APPOINTMENT_ID = UUID.randomUUID();

    private JwtDecoder jwtDecoder;
    private UserRepository userRepository;
    private ConsultationAccessService accessService;

    private WebSocketJwtInterceptor jwtInterceptor;
    private WebSocketAuthorizationInterceptor authorizationInterceptor;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(JwtDecoder.class);
        userRepository = mock(UserRepository.class);
        accessService = mock(ConsultationAccessService.class);

        jwtInterceptor = new WebSocketJwtInterceptor(
                jwtDecoder,
                userRepository
        );
        authorizationInterceptor =
                new WebSocketAuthorizationInterceptor(accessService);
    }

    // =========================================================
    // STOMP CONNECT authentication
    // =========================================================

    @Test
    void connectWithoutBearerTokenIsRejected() {
        assertThatThrownBy(() -> jwtInterceptor.preSend(
                connectMessage(null),
                mock(org.springframework.messaging.MessageChannel.class)
        ))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("authentication is required");
    }

    @Test
    void connectWithMalformedTokenIsRejected() {
        when(jwtDecoder.decode("bad-token"))
                .thenThrow(new JwtException("invalid"));

        assertThatThrownBy(() -> jwtInterceptor.preSend(
                connectMessage("bad-token"),
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connectWithUnknownUserIsRejected() {
        when(jwtDecoder.decode("token")).thenReturn(jwt());
        when(userRepository.findByIdWithRole(USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> jwtInterceptor.preSend(
                connectMessage("token"),
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connectWithSuspendedUserIsRejected() {
        when(jwtDecoder.decode("token")).thenReturn(jwt());
        User suspendedUser = user("CUSTOMER", "SUSPENDED", true);
        when(userRepository.findByIdWithRole(USER_ID))
                .thenReturn(Optional.of(suspendedUser));

        assertThatThrownBy(() -> jwtInterceptor.preSend(
                connectMessage("token"),
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connectWithActiveTokenAuthenticatesThePrincipal() {
        when(jwtDecoder.decode("token")).thenReturn(jwt());
        User activeUser = user("CUSTOMER", "ACTIVE", true);
        when(userRepository.findByIdWithRole(USER_ID))
                .thenReturn(Optional.of(activeUser));

        Message<?> message = connectMessage("token");

        Message<?> result = jwtInterceptor.preSend(
                message,
                mock(org.springframework.messaging.MessageChannel.class)
        );

        assertThat(result).isSameAs(message);

        StompHeaderAccessor accessor = MessageHeaderAccessor
                .getAccessor(result, StompHeaderAccessor.class);

        assertThat(accessor).isNotNull();
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName())
                .isEqualTo(USER_ID.toString());
        assertThat(((Authentication) accessor.getUser()).getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly("ROLE_CUSTOMER");
    }

    // =========================================================
    // STOMP SUBSCRIBE / SEND authorization
    // =========================================================

    @Test
    void subscribingToNotificationsRequiresAuthentication() {
        assertThatThrownBy(() -> authorizationInterceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", null),
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void authenticatedUserMaySubscribeToTheirOwnNotificationQueue() {
        Message<?> message = frame(
                StompCommand.SUBSCRIBE,
                "/user/queue/notifications",
                authentication(USER_ID, "ROLE_CUSTOMER")
        );

        assertThatCode(() -> authorizationInterceptor.preSend(
                message,
                mock(org.springframework.messaging.MessageChannel.class)
        )).doesNotThrowAnyException();
    }

    @Test
    void arbitraryDirectoriesCannotBeSubscribedTo() {
        assertThatThrownBy(() -> authorizationInterceptor.preSend(
                frame(
                        StompCommand.SUBSCRIBE,
                        "/topic/all-appointments",
                        authentication(USER_ID, "ROLE_CUSTOMER")
                ),
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void chatSubscriptionIsAllowedOnlyForConsultationParticipants() {
        when(accessService.canAccess(eq(APPOINTMENT_ID), any()))
                .thenReturn(false);

        Message<?> denied = frame(
                StompCommand.SUBSCRIBE,
                "/topic/appointments/" + APPOINTMENT_ID + "/chat",
                authentication(USER_ID, "ROLE_CUSTOMER")
        );

        assertThatThrownBy(() -> authorizationInterceptor.preSend(
                denied,
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);

        when(accessService.canAccess(eq(APPOINTMENT_ID), any()))
                .thenReturn(true);

        Message<?> allowed = frame(
                StompCommand.SUBSCRIBE,
                "/topic/appointments/" + APPOINTMENT_ID + "/chat",
                authentication(USER_ID, "ROLE_CUSTOMER")
        );

        assertThatCode(() -> authorizationInterceptor.preSend(
                allowed,
                mock(org.springframework.messaging.MessageChannel.class)
        )).doesNotThrowAnyException();
    }

    @Test
    void chatSendIsRejectedForNonParticipantsAndUnauthenticatedUsers() {
        Message<?> unauthenticated = frame(
                StompCommand.SEND,
                "/app/appointments/" + APPOINTMENT_ID + "/chat",
                null
        );

        assertThatThrownBy(() -> authorizationInterceptor.preSend(
                unauthenticated,
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);

        when(accessService.canAccess(eq(APPOINTMENT_ID), any()))
                .thenReturn(false);

        Message<?> nonParticipant = frame(
                StompCommand.SEND,
                "/app/appointments/" + APPOINTMENT_ID + "/chat",
                authentication(USER_ID, "ROLE_ADMIN")
        );

        assertThatThrownBy(() -> authorizationInterceptor.preSend(
                nonParticipant,
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void chatSendIsAllowedForParticipants() {
        when(accessService.canAccess(eq(APPOINTMENT_ID), any()))
                .thenReturn(true);

        Message<?> allowed = frame(
                StompCommand.SEND,
                "/app/appointments/" + APPOINTMENT_ID + "/chat",
                authentication(USER_ID, "ROLE_DOCTOR")
        );

        assertThatCode(() -> authorizationInterceptor.preSend(
                allowed,
                mock(org.springframework.messaging.MessageChannel.class)
        )).doesNotThrowAnyException();
    }

    @Test
    void sendToAnArbitraryDestinationIsRejected() {
        assertThatThrownBy(() -> authorizationInterceptor.preSend(
                frame(
                        StompCommand.SEND,
                        "/app/admin/anything",
                        authentication(USER_ID, "ROLE_ADMIN")
                ),
                mock(org.springframework.messaging.MessageChannel.class)
        )).isInstanceOf(MessageDeliveryException.class);
    }

    // =========================================================
    // Helpers
    // =========================================================

    private Jwt jwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .claim("role", "CUSTOMER")
                .build();
    }

    private User user(
            String roleName,
            String accountStatus,
            boolean emailVerified
    ) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(roleName);

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getRole()).thenReturn(role);
        when(user.getAccountStatus()).thenReturn(accountStatus);
        when(user.isEmailVerified()).thenReturn(emailVerified);

        return user;
    }

    private Authentication authentication(UUID userId, String authority) {
        return new UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    private Message<?> connectMessage(String token) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);

        if (token != null) {
            accessor.setNativeHeader("Authorization", "Bearer " + token);
        }

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }

    private Message<?> frame(
            StompCommand command,
            String destination,
            Authentication user
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);

        if (user != null) {
            accessor.setUser(user);
        }

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }
}
