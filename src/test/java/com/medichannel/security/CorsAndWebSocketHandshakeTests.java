package com.medichannel.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/medichannel_test",
    "spring.datasource.username=postgres",
    "spring.datasource.password=local_test_only_password",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false",
    "spring.jpa.open-in-view=false",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "APP_FRONTEND_ORIGIN=http://localhost:3000,https://medichannel.netlify.app"
})
@AutoConfigureMockMvc
class CorsAndWebSocketHandshakeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromFirstAllowedOriginIsAccepted() throws Exception {
        mockMvc.perform(
            options("/api/v1/doctors")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
        )
            .andExpect(status().isOk())
            .andExpect(header().string(
                "Access-Control-Allow-Origin", "http://localhost:3000"))
            .andExpect(header().doesNotExist(
                "Access-Control-Allow-Credentials"));
    }

    @Test
    void preflightFromSecondAllowedOriginIsAcceptedForAuthenticatedPost()
            throws Exception {
        mockMvc.perform(
            options("/api/v1/appointments")
                .header("Origin", "https://medichannel.netlify.app")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers",
                        "authorization,content-type")
        )
            .andExpect(status().isOk())
            .andExpect(header().string(
                "Access-Control-Allow-Origin",
                "https://medichannel.netlify.app"));
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(
            options("/api/v1/doctors")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")
        ).andExpect(status().isForbidden());
    }

    @Test
    void webSocketHandshakeIsNotBlockedByHttpAuthentication()
            throws Exception {
        // A plain GET is not a real handshake, so the handshake handler may
        // answer 400. The point is that Spring Security must NOT answer
        // 401/403 first, because browsers cannot send an Authorization
        // header while opening a WebSocket. The JWT is checked later, on
        // the STOMP CONNECT frame.
        int httpStatus = mockMvc.perform(get("/ws"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(httpStatus).isNotIn(401, 403);
    }

    @Test
    void otherUnauthenticatedEndpointsStillRequireLogin() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/me"))
                .andExpect(status().isUnauthorized());
    }
}
