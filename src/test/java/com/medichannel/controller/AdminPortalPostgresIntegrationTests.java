package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@Transactional
class AdminPortalPostgresIntegrationTests {

    private static final String ADMIN_URL = "/api/v1/admin";
    private static final String AUTH_URL = "/api/v1/auth";
    private static final String REGISTER_PASSWORD = "StrongPass12345!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SpecialisationRepository specialisationRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private PatientDataProtectionService dataProtectionService;

    @Test
    void adminEndpointsRejectCustomersDoctorsAndAnonymousUsers()
            throws Exception {

        TestFixture fixture = createFixture();

        mockMvc.perform(
                get(ADMIN_URL + "/stats")
                        .with(customerJwt(fixture.patientUserId()))
        ).andExpect(status().isForbidden());

        mockMvc.perform(
                get(ADMIN_URL + "/users")
                        .with(doctorJwt(fixture.doctorUserId()))
        ).andExpect(status().isForbidden());

        mockMvc.perform(
                get(ADMIN_URL + "/appointments")
                        .with(customerJwt(fixture.patientUserId()))
        ).andExpect(status().isForbidden());

        mockMvc.perform(get(ADMIN_URL + "/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanReadUsersStatsAppointmentsAndPayments() throws Exception {

        TestFixture fixture = createFixture();

        String appointmentId = bookAppointment(fixture);
        payForAppointment(appointmentId, fixture.patientUserId());

        // User directory.
        String usersBody = mockMvc.perform(
                get(ADMIN_URL + "/users")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode users = objectMapper.readTree(usersBody);
        List<String> emails = new java.util.ArrayList<>();
        users.forEach(user -> emails.add(user.get("email").asText()));

        assertThat(emails)
                .contains(fixture.patientEmail())
                .contains(fixture.doctorEmail());

        // Individual user.
        mockMvc.perform(
                get(ADMIN_URL + "/users/" + fixture.patientUserId())
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));

        // Dashboard statistics.
        mockMvc.perform(
                get(ADMIN_URL + "/stats")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.totalAppointments")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.completedPayments")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.completedPaymentTotalLkr")
                        .value(1500.00));

        // Oversight of appointments.
        mockMvc.perform(
                get(ADMIN_URL + "/appointments")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].appointmentId")
                        .value(appointmentId))
                .andExpect(jsonPath("$[0].paymentStatus").value("PAID"));

        mockMvc.perform(
                get(ADMIN_URL + "/appointments/" + appointmentId)
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").value(appointmentId))
                .andExpect(jsonPath("$.patientName").value("Test Patient"));

        // Payment oversight.
        mockMvc.perform(
                get(ADMIN_URL + "/payments")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].appointmentId")
                        .value(appointmentId))
                .andExpect(jsonPath("$[0].patientName").value("Test Patient"));

        mockMvc.perform(
                get(ADMIN_URL + "/appointments/" + appointmentId + "/payments")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void adminCannotSuspendOrDisableOwnAccount() throws Exception {

        TestFixture fixture = createFixture();

        mockMvc.perform(
                patch(ADMIN_URL + "/users/" + fixture.adminUserId()
                        + "/suspend")
                        .with(adminJwt(fixture.adminUserId()))
        ).andExpect(status().isConflict());

        mockMvc.perform(
                patch(ADMIN_URL + "/users/" + fixture.adminUserId()
                        + "/disable")
                        .with(adminJwt(fixture.adminUserId()))
        ).andExpect(status().isConflict());

        mockMvc.perform(
                get(ADMIN_URL + "/users/" + fixture.adminUserId())
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
    }

    @Test
    void suspendedCustomerCannotLogInUntilAnAdminReactivatesThem()
            throws Exception {

        TestFixture fixture = createFixture();

        String email = "admin.flow." + UUID.randomUUID()
                .toString()
                .replace("-", "") + "@example.test";

        String registerBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "email", email,
                        "password", REGISTER_PASSWORD,
                        "confirmPassword", REGISTER_PASSWORD
                )
        );

        String registered = mockMvc.perform(
                post(AUTH_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID customerUserId = UUID.fromString(
                objectMapper.readTree(registered).get("userId").asText()
        );

        String loginBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "email", email,
                        "password", REGISTER_PASSWORD
                )
        );

        mockMvc.perform(
                post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        mockMvc.perform(
                patch(ADMIN_URL + "/users/" + customerUserId + "/suspend")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("SUSPENDED"));

        mockMvc.perform(
                post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody)
        ).andExpect(status().isUnauthorized());

        mockMvc.perform(
                patch(ADMIN_URL + "/users/" + customerUserId + "/activate")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));

        mockMvc.perform(
                post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody)
        ).andExpect(status().isOk());
    }

    @Test
    void adminCanUnlockAnAccountLockedByFailedLogins() throws Exception {

        TestFixture fixture = createFixture();

        String email = "admin.unlock." + UUID.randomUUID()
                .toString()
                .replace("-", "") + "@example.test";

        String registerBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "email", email,
                        "password", REGISTER_PASSWORD,
                        "confirmPassword", REGISTER_PASSWORD
                )
        );

        String registered = mockMvc.perform(
                post(AUTH_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody)
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID customerUserId = UUID.fromString(
                objectMapper.readTree(registered).get("userId").asText()
        );

        String wrongPasswordBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "email", email,
                        "password", "WrongPassword12345"
                )
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(
                    post(AUTH_URL + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(wrongPasswordBody)
            ).andExpect(status().isUnauthorized());
        }

        String correctPasswordBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "email", email,
                        "password", REGISTER_PASSWORD
                )
        );

        mockMvc.perform(
                post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctPasswordBody)
        ).andExpect(status().isUnauthorized());

        mockMvc.perform(
                patch(ADMIN_URL + "/users/" + customerUserId + "/unlock")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedLoginAttempts").value(0))
                .andExpect(jsonPath("$.lockedUntil").doesNotExist());

        mockMvc.perform(
                post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctPasswordBody)
        ).andExpect(status().isOk());
    }

    @Test
    void adminUserFiltersAcceptOnlyKnownRoles() throws Exception {

        TestFixture fixture = createFixture();

        mockMvc.perform(
                get(ADMIN_URL + "/users")
                        .param("role", "CUSTOMER")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("CUSTOMER"));

        mockMvc.perform(
                get(ADMIN_URL + "/users")
                        .param("role", "NOT_A_ROLE")
                        .with(adminJwt(fixture.adminUserId()))
        ).andExpect(status().isBadRequest());
    }

    // =========================================================
    // Helpers
    // =========================================================

    private String bookAppointment(TestFixture fixture) throws Exception {

        String responseBody = mockMvc.perform(
                post("/api/v1/appointments")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "sessionId", fixture.sessionId(),
                                        "reason", "Admin integration test"
                                )
                        ))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(responseBody)
                .get("appointmentId")
                .asText();
    }

    private void payForAppointment(String appointmentId, UUID patientUserId)
            throws Exception {

        mockMvc.perform(
                post("/api/v1/appointments/" + appointmentId + "/payments")
                        .with(customerJwt(patientUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "CARD"}
                                """)
        ).andExpect(status().isCreated());
    }

    private TestFixture createFixture() {

        String unique = UUID.randomUUID()
                .toString()
                .replace("-", "");

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow();
        Role doctorRole = roleRepository.findByName("DOCTOR")
                .orElseThrow();
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow();

        User adminUser = new User(
                adminRole,
                "admin.portal." + unique + "@example.test",
                "test-password-hash"
        );
        adminUser.activateAfterVerification();
        adminUser = userRepository.saveAndFlush(adminUser);

        String doctorEmail = "admin.doctor." + unique + "@example.test";
        User doctorUser = userRepository.saveAndFlush(
                new User(doctorRole, doctorEmail, "test-password-hash")
        );

        Specialisation specialisation = specialisationRepository
                .saveAndFlush(new Specialisation(
                        "Admin Test " + unique,
                        "Temporary admin test"
                ));

        Doctor doctor = doctorRepository.saveAndFlush(
                new Doctor(
                        doctorUser,
                        specialisation,
                        "Admin",
                        "TestDoctor",
                        "ADMIN-" + unique,
                        "Test qualification",
                        "Temporary admin integration test",
                        new BigDecimal("1500.00")
                )
        );

        String patientEmail = "admin.patient." + unique + "@example.test";
        User patientUser = new User(
                customerRole,
                patientEmail,
                "test-password-hash"
        );
        patientUser.activateAfterVerification();
        patientUser = userRepository.saveAndFlush(patientUser);

        Patient patient = patientRepository.saveAndFlush(
                new Patient(
                        patientUser,
                        dataProtectionService.encrypt("Test"),
                        dataProtectionService.encrypt("Patient"),
                        dataProtectionService.encrypt("0771234567"),
                        null,
                        dataProtectionService.encrypt("2000-01-01"),
                        dataProtectionService.encrypt("OTHER")
                )
        );

        Room room = roomRepository.saveAndFlush(
                new Room(
                        "ADMIN-" + unique.substring(0, 12),
                        "Admin Test Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        4
                )
        );

        DoctorSession session = new DoctorSession(
                doctor,
                room,
                LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(30),
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                ConsultationType.PHYSICAL,
                4
        );
        session.updateSchedulingDetails(10, new BigDecimal("1500.00"));
        session.open();
        session = doctorSessionRepository.saveAndFlush(session);

        return new TestFixture(
                adminUser.getId(),
                doctorUser.getId(),
                doctor.getId(),
                patientUser.getId(),
                patient.getId(),
                session.getId(),
                patientEmail,
                doctorEmail
        );
    }

    private RequestPostProcessor customerJwt(UUID userId) {
        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "CUSTOMER")
                        .claim("email", "admin-test-customer@example.test")
                )
                .authorities(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")
                );
    }

    private RequestPostProcessor doctorJwt(UUID userId) {
        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "DOCTOR")
                        .claim("email", "admin-test-doctor@example.test")
                )
                .authorities(
                        new SimpleGrantedAuthority("ROLE_DOCTOR")
                );
    }

    private RequestPostProcessor adminJwt(UUID userId) {
        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "ADMIN")
                        .claim("email", "admin-test-admin@example.test")
                )
                .authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                );
    }

    private record TestFixture(
            UUID adminUserId,
            UUID doctorUserId,
            UUID doctorId,
            UUID patientUserId,
            UUID patientId,
            UUID sessionId,
            String patientEmail,
            String doctorEmail
    ) {
    }
}
