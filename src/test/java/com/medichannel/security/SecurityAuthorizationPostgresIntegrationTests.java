package com.medichannel.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
@AutoConfigureMockMvc
class SecurityAuthorizationPostgresIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void customerCannotReadRoomManagementEndpoint() throws Exception {
        mockMvc.perform(
            get("/api/v1/rooms")
                .with(jwt().jwt(token ->
                    token.claim("role", "CUSTOMER")
                ))
        ).andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotReadRoomManagementEndpoint() throws Exception {
        mockMvc.perform(
            get("/api/v1/rooms")
                .with(jwt().jwt(token ->
                    token.claim("role", "DOCTOR")
                ))
        ).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotAccessDoctorCreationEndpoint() throws Exception {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/doctors")
                .with(jwt().jwt(token ->
                    token.claim("role", "CUSTOMER")
                ))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotCreateSpecialisation() throws Exception {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/specialisations")
                .with(jwt().jwt(token ->
                    token.claim("role", "DOCTOR")
                ))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedUserCannotReadRoomManagementEndpoint()
            throws Exception {
        mockMvc.perform(
            get("/api/v1/rooms")
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedUserCannotReadCurrentUserEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanReadCurrentUserEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").with(jwt().jwt(jwt -> jwt
                        .subject("94d1f5a4-b77e-4d33-a7f4-13b9d1da1fa3")
                        .claim("email", "customer@example.com")
                        .claim("role", "CUSTOMER"))))
                .andExpect(status().isOk());
    }

    @Test
    void malformedBearerTokenIsRejected() throws Exception {
        mockMvc.perform(
            get("/api/v1/rooms")
                .header("Authorization", "Bearer this-is-not-a-valid-jwt")
        ).andExpect(status().isUnauthorized());
    }
}
