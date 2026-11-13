package com.medichannel.config;

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
        registry.addEndpoint("/ws")
                .setAllowedOrigins(frontendOrigin.trim().split("\\s*,\\s*"));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024);
        registration.setSendBufferSizeLimit(512 * 1024);
        registration.setSendTimeLimit(20 * 1000);
        registration.setTimeToFirstMessage(30 * 1000);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                jwtInterceptor,
                authorizationInterceptor
        );
    }
}
