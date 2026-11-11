package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
class AppointmentApiPostgresIntegrationTests {

    private static final String APPOINTMENTS_URL =
            "/api/v1/appointments";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void customerCanBookViewAndCancelOwnAppointment() throws Exception {

        TestFixture fixture = createFixture();

        String bookingRequest = bookingJson(
                fixture.sessionId(),
                "Persistent headache"
        );

        // 1. Book an appointment.
        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequest)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentNumber").value(1))
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode bookingResponse = objectMapper.readTree(responseBody);

        String appointmentId = bookingResponse
                .get("appointmentId")
                .asText();

        assertThat(appointmentId).isNotBlank();

        // 2. Verify the customer's appointment history.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].appointmentId")
                        .value(appointmentId));

        // 3. Retrieve the individual appointment.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId)
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId")
                        .value(appointmentId))
                .andExpect(jsonPath("$.appointmentNumber").value(1));

        // 4. Cancel the unpaid appointment.
        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/cancel")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 5. Confirm cancellation remains in appointment history.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));

        // 6. Confirm the database retained the cancelled record.
        Integer appointmentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM appointments
                WHERE id = ?
                  AND status = 'CANCELLED'
                  AND cancelled_at IS NOT NULL
                """,
                Integer.class,
                UUID.fromString(appointmentId)
        );

        assertThat(appointmentCount).isEqualTo(1);
    }

    @Test
    void customerCannotBookTwiceInTheSameSession() throws Exception {

        TestFixture fixture = createFixture();

        String request = bookingJson(
                fixture.sessionId(),
                "First booking"
        );

        mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
                .andExpect(status().isCreated());

        // The same patient attempts another active booking.
        mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                fixture.sessionId(),
                                "Duplicate booking"
                        ))
        )
                .andExpect(status().isConflict());

        Integer appointmentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM appointments
                WHERE session_id = ?
                """,
                Integer.class,
                fixture.sessionId()
        );

        Integer appointmentCounter = jdbcTemplate.queryForObject(
                """
                SELECT last_appointment_number
                FROM doctor_sessions
                WHERE id = ?
                """,
                Integer.class,
                fixture.sessionId()
        );

        assertThat(appointmentCount).isEqualTo(1);
        assertThat(appointmentCounter).isEqualTo(1);
    }

    @Test
    void customerCannotRetrieveAnotherCustomersAppointment()
            throws Exception {

        TestFixture owner = createFixture();
        TestFixture anotherCustomer = createFixture();

        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(owner.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                owner.sessionId(),
                                "Private appointment"
                        ))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String appointmentId = objectMapper
                .readTree(responseBody)
                .get("appointmentId")
                .asText();

        // A different authenticated customer must not access it.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId)
                        .with(customerJwt(
                                anotherCustomer.patientUserId()
                        ))
        )
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedCustomerCannotBookAnAppointment()
            throws Exception {

        TestFixture fixture = createFixture();

        mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                fixture.sessionId(),
                                "Unauthenticated booking"
                        ))
        )
                .andExpect(status().isUnauthorized());
    }

    private TestFixture createFixture() {

        String unique = UUID.randomUUID()
                .toString()
                .replace("-", "");

        Role doctorRole = roleRepository.findByName("DOCTOR")
                .orElseThrow();

        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow();

        // Create a doctor account.
        User doctorUser = userRepository.saveAndFlush(
                new User(
                        doctorRole,
                        "api.doctor." + unique + "@example.test",
                        "test-password-hash"
                )
        );

        Specialisation specialisation =
                specialisationRepository.saveAndFlush(
                        new Specialisation(
                                "API Test " + unique,
                                "Temporary appointment API test"
                        )
                );

        Doctor doctor = doctorRepository.saveAndFlush(
                new Doctor(
                        doctorUser,
                        specialisation,
                        "API",
                        "TestDoctor",
                        "API-TEST-" + unique,
                        "Test qualification",
                        "Temporary API integration test",
                        new BigDecimal("1500.00")
                )
        );

        // Create an active, email-verified customer.
        User patientUser = new User(
                customerRole,
                "api.patient." + unique + "@example.test",
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

        // Create a second customer for ownership testing.
        User secondPatientUser = new User(
                customerRole,
                "api.patient.second." + unique + "@example.test",
                "test-password-hash"
        );

        secondPatientUser.activateAfterVerification();

        secondPatientUser = userRepository.saveAndFlush(
                secondPatientUser
        );

        patientRepository.saveAndFlush(
                new Patient(
                        secondPatientUser,
                        dataProtectionService.encrypt("Second"),
                        dataProtectionService.encrypt("Patient"),
                        dataProtectionService.encrypt("0771234568"),
                        null,
                        dataProtectionService.encrypt("2001-01-01"),
                        dataProtectionService.encrypt("OTHER")
                )
        );

        Room room = roomRepository.saveAndFlush(
                new Room(
                        "API-" + unique.substring(0, 12),
                        "API Test Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        4
                )
        );

        DoctorSession session = new DoctorSession(
                doctor,
                room,
                LocalDate.now(
                        ZoneId.of("Asia/Colombo")
                ).plusDays(30),
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                ConsultationType.PHYSICAL,
                4
        );

        session.updateSchedulingDetails(
                10,
                new BigDecimal("1500.00")
        );

        session.open();

        session = doctorSessionRepository.saveAndFlush(session);

        return new TestFixture(
                patientUser.getId(),
                patient.getId(),
                secondPatientUser.getId(),
                session.getId()
        );
    }

    private String bookingJson(UUID sessionId, String reason) {

        try {
            return objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "sessionId", sessionId,
                            "reason", reason
                    )
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not create test booking request."
            );
        }
    }

    private org.springframework.test.web.servlet.request
            .RequestPostProcessor customerJwt(UUID userId) {

        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "CUSTOMER")
                        .claim("email", "api-test@example.test")
                )
                .authorities(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")
                );
    }

    private record TestFixture(
            UUID patientUserId,
            UUID patientId,
            UUID secondPatientUserId,
            UUID sessionId
    ) {
    }
}
