package com.medichannel.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.medichannel.security.WebSocketAuthorizationInterceptor;
import com.medichannel.security.WebSocketJwtInterceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketJwtInterceptor jwtInterceptor;
    private final WebSocketAuthorizationInterceptor authorizationInterceptor;
    private final String frontendOrigin;

    public WebSocketConfig(
            WebSocketJwtInterceptor jwtInterceptor,
            WebSocketAuthorizationInterceptor authorizationInterceptor,
            @Value("${APP_FRONTEND_ORIGIN:http://localhost:3000}")
            String frontendOrigin
    ) {
        this.jwtInterceptor = jwtInterceptor;
        this.authorizationInterceptor = authorizationInterceptor;
        this.frontendOrigin = frontendOrigin;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> allowedPatterns = new ArrayList<>(
                Arrays.asList(frontendOrigin.trim().split("\\s*,\\s*"))
        );
        // Vercel preview and alias hostnames rotate; keep live chat working
        // from any Vercel-served frontend while explicit origins stay pinned.
        if (!allowedPatterns.contains("https://*.vercel.app")) {
            allowedPatterns.add("https://*.vercel.app");
        }

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedPatterns.toArray(String[]::new));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024);
        registration.setSendBufferSizeLimit(512 * 1024);
        registration.setSendTimeLimit(20 * 1000);
        registration.setTimeToFirstMessage(30 * 1000);
    }

    /**
     * Bound individual STOMP frames and slow-client buffering.
     * Chat text is separately limited to 1,000 characters by ChatService.
     */
    @Override
    public void configureWebSocketTransport(
            WebSocketTransportRegistration registration
    ) {
        registration
                .setMessageSizeLimit(16 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(10_000);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                jwtInterceptor,
                authorizationInterceptor
        );
    }
}
