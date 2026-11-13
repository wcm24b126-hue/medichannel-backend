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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.chat.ChatMessageResponse;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Notification;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.NotificationType;
import com.medichannel.enums.RoomType;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.NotificationRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;
import com.medichannel.service.ChatService;
import com.medichannel.service.ConsultationAccessService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class ConsultationLifecyclePostgresIntegrationTests {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String DOCTOR_URL = "/api/v1/doctor/appointments";
    private static final String NOTIFICATIONS_URL = "/api/v1/notifications";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ConsultationAccessService consultationAccessService;

    @Autowired
    private NotificationRepository notificationRepository;

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
    void patientAndDoctorCompleteTheFullConsultationLifecycle()
            throws Exception {

        TestFixture fixture = createFixture();

        String appointmentId = bookAppointment(
                fixture.sessionId(),
                fixture.patientUserId()
        );

        // 1. The patient checks in.
        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/check-in")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.arrivedAt").isNotEmpty());

        // 2. A consultation cannot start before payment.
        mockMvc.perform(
                patch(DOCTOR_URL + "/" + appointmentId + "/start")
                        .with(doctorJwt(fixture.doctorUserId()))
        )
                .andExpect(status().isConflict());

        // 3. The patient records a simulated card payment.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + appointmentId + "/payments")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "CARD"}
                                """)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId").value(appointmentId))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.method").value("CARD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.paymentReference")
                        .value(org.hamcrest.Matchers.startsWith("PAY-")))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

        // 4. The doctor starts and completes the consultation.
        mockMvc.perform(
                patch(DOCTOR_URL + "/" + appointmentId + "/start")
                        .with(doctorJwt(fixture.doctorUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_CONSULTATION"))
                .andExpect(jsonPath("$.consultationStartedAt").isNotEmpty());

        mockMvc.perform(
                patch(DOCTOR_URL + "/" + appointmentId + "/complete")
                        .with(doctorJwt(fixture.doctorUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.consultationCompletedAt").isNotEmpty());

        // 5. The persisted appointment reflects the lifecycle.
        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT status, payment_status, arrived_at,
                       consultation_started_at, consultation_completed_at
                FROM appointments
                WHERE id = ?
                """,
                UUID.fromString(appointmentId)
        );

        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("payment_status")).isEqualTo("PAID");
        assertThat(row.get("arrived_at")).isNotNull();
        assertThat(row.get("consultation_started_at")).isNotNull();
        assertThat(row.get("consultation_completed_at")).isNotNull();

        // Invalid repeated transitions are rejected as conflicts, not
        // as server errors.
        for (String action : java.util.List.of(
                "arrive", "start", "complete", "no-show"
        )) {
            mockMvc.perform(
                    patch(DOCTOR_URL + "/" + appointmentId + "/" + action)
                            .with(doctorJwt(fixture.doctorUserId()))
            ).andExpect(status().isConflict());
        }

        // 6. Payment history is available to the patient.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId + "/payments")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(
                get("/api/v1/payments/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].appointmentId")
                        .value(appointmentId));

        // 7. Both participants receive lifecycle notifications.
        List<Notification> patientNotifications = notificationRepository
                .findByUser_IdOrderByCreatedAtDesc(fixture.patientUserId());

        assertThat(patientNotifications)
                .extracting(Notification::getType)
                .contains(
                        NotificationType.APPOINTMENT_BOOKED,
                        NotificationType.PAYMENT_RECEIVED,
                        NotificationType.CONSULTATION_STARTED,
                        NotificationType.CONSULTATION_COMPLETED
                );

        List<Notification> doctorNotifications = notificationRepository
                .findByUser_IdOrderByCreatedAtDesc(fixture.doctorUserId());

        assertThat(doctorNotifications)
                .extracting(Notification::getType)
                .contains(
                        NotificationType.APPOINTMENT_BOOKED,
                        NotificationType.APPOINTMENT_ARRIVED,
                        NotificationType.PAYMENT_RECEIVED
                );
    }

    @Test
    void duplicatePaymentAndCancelledAppointmentPaymentAreRejected()
            throws Exception {

        TestFixture fixture = createFixture();

        String paidAppointmentId = bookAppointment(
                fixture.sessionId(),
                fixture.patientUserId()
        );

        recordPayment(paidAppointmentId, fixture.patientUserId());

        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + paidAppointmentId + "/payments")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "CASH"}
                                """)
        )
                .andExpect(status().isConflict());

        // A second, unpaid booking can be cancelled and then paid for
        // by the doctor's session. Use a different session because the
        // patient already has an active appointment in the first one.
        String cancelledAppointmentId = bookAppointment(
                fixture.secondSessionId(),
                fixture.patientUserId()
        );

        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + cancelledAppointmentId
                        + "/cancel")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk());

        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + cancelledAppointmentId
                        + "/payments")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "CASH"}
                                """)
        )
                .andExpect(status().isConflict());
    }

    @Test
    void onlyTheAssignedDoctorCanPublishASummaryAfterCompletion()
            throws Exception {

        TestFixture fixture = createFixture();

        String completedAppointmentId = bookAppointment(
                fixture.sessionId(),
                fixture.patientUserId()
        );
        recordPayment(completedAppointmentId, fixture.patientUserId());
        startConsultation(completedAppointmentId, fixture.doctorUserId());
        completeConsultation(completedAppointmentId, fixture.doctorUserId());

        String summaryBody = summaryJson(
                "Persistent headaches",
                "Tension headache",
                "Rest and hydration",
                "Paracetamol 500mg",
                "Review in two weeks",
                LocalDate.now().plusMonths(3).toString()
        );

        // A different doctor must not publish the summary.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(doctorJwt(fixture.secondDoctorUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(summaryBody)
        )
                .andExpect(status().isForbidden());

        // Patients cannot publish summaries.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(summaryBody)
        )
                .andExpect(status().isForbidden());

        // The assigned doctor can publish it.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(doctorJwt(fixture.doctorUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(summaryBody)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId")
                        .value(completedAppointmentId))
                .andExpect(jsonPath("$.diagnosis")
                        .value("Tension headache"))
                .andExpect(jsonPath("$.doctorName")
                        .value("API TestDoctor"));

        // Clinical free text is encrypted at rest.
        String storedDiagnosis = jdbcTemplate.queryForObject(
                """
                SELECT diagnosis_encrypted
                FROM consultation_summaries
                WHERE appointment_id = ?
                """,
                String.class,
                UUID.fromString(completedAppointmentId)
        );

        assertThat(storedDiagnosis)
                .isNotBlank()
                .doesNotContain("Tension headache");

        // The patient can read the summary.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis")
                        .value("Tension headache"));

        // An unrelated patient cannot.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(customerJwt(fixture.secondPatientUserId()))
        )
                .andExpect(status().isForbidden());

        // Staff have NO clinical read access (brief §2 privacy rule).
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isForbidden());

        // The assigned doctor can update the summary.
        mockMvc.perform(
                put(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(doctorJwt(fixture.doctorUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(summaryJson(
                                "Persistent headaches",
                                "Migraine",
                                "Avoid triggers",
                                "Sumatriptan",
                                "Review in four weeks",
                                null
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis").value("Migraine"));

        // A second summary is rejected.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + completedAppointmentId
                        + "/summary")
                        .with(doctorJwt(fixture.doctorUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(summaryBody)
        )
                .andExpect(status().isConflict());

        // A summary cannot be created before the consultation completes.
        String bookedAppointmentId = bookAppointment(
                fixture.secondSessionId(),
                fixture.patientUserId()
        );

        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + bookedAppointmentId
                        + "/summary")
                        .with(doctorJwt(fixture.doctorUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(summaryBody)
        )
                .andExpect(status().isConflict());
    }

    @Test
    void reviewsRequireCompletedAppointmentAndAreUnique()
            throws Exception {

        TestFixture fixture = createFixture();

        String bookedAppointmentId = bookAppointment(
                fixture.sessionId(),
                fixture.patientUserId()
        );

        // Feedback before completion is rejected.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + bookedAppointmentId + "/review")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating": 5, "comment": "Great"}
                                """)
        )
                .andExpect(status().isConflict());

        recordPayment(bookedAppointmentId, fixture.patientUserId());
        startConsultation(bookedAppointmentId, fixture.doctorUserId());
        completeConsultation(bookedAppointmentId, fixture.doctorUserId());

        // Only the patient who attended can review.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + bookedAppointmentId + "/review")
                        .with(customerJwt(fixture.secondPatientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating": 4, "comment": "Not mine"}
                                """)
        )
                .andExpect(status().isForbidden());

        // Out-of-range ratings fail validation.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + bookedAppointmentId + "/review")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating": 0, "comment": "Invalid"}
                                """)
        )
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + bookedAppointmentId + "/review")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating": 5, "comment": "Excellent care"}
                                """)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Excellent care"));

        // Duplicate feedback is rejected.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + bookedAppointmentId + "/review")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating": 3, "comment": "Second attempt"}
                                """)
        )
                .andExpect(status().isConflict());

        mockMvc.perform(
                get("/api/v1/reviews/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Public doctor review summary.
        mockMvc.perform(
                get("/api/v1/doctors/" + fixture.doctorId() + "/reviews")
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.averageRating").value(5.0))
                .andExpect(jsonPath("$.reviews[0].comment")
                        .value("Excellent care"));

        // The doctor is notified about the new feedback.
        assertThat(notificationRepository
                .findByUser_IdOrderByCreatedAtDesc(fixture.doctorUserId()))
                .extracting(Notification::getType)
                .contains(NotificationType.REVIEW_RECEIVED);
    }

    @Test
    void chatIsPrivateToThePatientAndAssignedDoctor() throws Exception {

        TestFixture fixture = createFixture();

        String appointmentId = bookAppointment(
                fixture.sessionId(),
                fixture.patientUserId()
        );

        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId + "/chat/messages")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId + "/chat/messages")
                        .with(doctorJwt(fixture.doctorUserId()))
        )
                .andExpect(status().isOk());

        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId + "/chat/messages")
                        .with(customerJwt(fixture.secondPatientUserId()))
        )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId + "/chat/messages")
                        .with(adminJwt(fixture.adminUserId()))
        )
                .andExpect(status().isForbidden());

        // The WebSocket authorization service applies exactly the same
        // participant rules as the HTTP chat history endpoint.
        Authentication patientAuth = authentication(
                fixture.patientUserId(),
                "ROLE_CUSTOMER"
        );
        Authentication doctorAuth = authentication(
                fixture.doctorUserId(),
                "ROLE_DOCTOR"
        );
        Authentication otherPatientAuth = authentication(
                fixture.secondPatientUserId(),
                "ROLE_CUSTOMER"
        );
        Authentication adminAuth = authentication(
                fixture.adminUserId(),
                "ROLE_ADMIN"
        );

        assertThat(consultationAccessService.canAccess(
                UUID.fromString(appointmentId),
                patientAuth
        )).isTrue();

        assertThat(consultationAccessService.canAccess(
                UUID.fromString(appointmentId),
                doctorAuth
        )).isTrue();

        assertThat(consultationAccessService.canAccess(
                UUID.fromString(appointmentId),
                otherPatientAuth
        )).isFalse();

        assertThat(consultationAccessService.canAccess(
                UUID.fromString(appointmentId),
                adminAuth
        )).isFalse();

        // BR-09: the room is read-only until the consultation starts.
        assertThatThrownBy(() -> chatService.sendMessage(
                UUID.fromString(appointmentId),
                fixture.patientUserId(),
                "Too early"
        )).isInstanceOf(ConflictException.class);

        recordPayment(appointmentId, fixture.patientUserId());
        startConsultation(appointmentId, fixture.doctorUserId());

        // Messages are persisted with the authenticated sender.
        ChatMessageResponse patientMessage = chatService.sendMessage(
                UUID.fromString(appointmentId),
                fixture.patientUserId(),
                "Hello doctor"
        );

        assertThat(patientMessage.senderUserId())
                .isEqualTo(fixture.patientUserId());
        assertThat(patientMessage.senderRole()).isEqualTo("CUSTOMER");
        assertThat(patientMessage.senderName()).isEqualTo("Test Patient");

        ChatMessageResponse doctorMessage = chatService.sendMessage(
                UUID.fromString(appointmentId),
                fixture.doctorUserId(),
                "Hello, how can I help?"
        );

        assertThat(doctorMessage.senderRole()).isEqualTo("DOCTOR");

        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId + "/chat/messages")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("Hello doctor"))
                .andExpect(jsonPath("$[1].senderRole").value("DOCTOR"));

        // A third party cannot inject a message.
        assertThatThrownBy(() -> chatService.sendMessage(
                UUID.fromString(appointmentId),
                fixture.secondPatientUserId(),
                "Spoofed message"
        )).isInstanceOf(ForbiddenOperationException.class);

        // Completed consultations stop accepting messages.
        completeConsultation(appointmentId, fixture.doctorUserId());

        assertThatThrownBy(() -> chatService.sendMessage(
                UUID.fromString(appointmentId),
                fixture.patientUserId(),
                "Message after completion"
        )).isInstanceOf(ConflictException.class);
    }

    @Test
    void notificationsArePrivateAndCanBeMarkedRead() throws Exception {

        TestFixture fixture = createFixture();

        String appointmentId = bookAppointment(
                fixture.sessionId(),
                fixture.patientUserId()
        );

        String body = mockMvc.perform(
                get(NOTIFICATIONS_URL)
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String notificationId = objectMapper.readTree(body)
                .get(0)
                .get("notificationId")
                .asText();

        mockMvc.perform(
                get(NOTIFICATIONS_URL + "/unread-count")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        // Another user cannot mark this notification as read.
        mockMvc.perform(
                patch(NOTIFICATIONS_URL + "/" + notificationId + "/read")
                        .with(customerJwt(fixture.secondPatientUserId()))
        )
                .andExpect(status().isNotFound());

        mockMvc.perform(
                patch(NOTIFICATIONS_URL + "/" + notificationId + "/read")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        mockMvc.perform(
                get(NOTIFICATIONS_URL + "/unread-count")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        // A doctor action produces another unread notification for the
        // patient, which can then be cleared in bulk.
        mockMvc.perform(
                patch(DOCTOR_URL + "/" + appointmentId + "/arrive")
                        .with(doctorJwt(fixture.doctorUserId()))
        )
                .andExpect(status().isOk());

        mockMvc.perform(
                get(NOTIFICATIONS_URL + "/unread-count")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        mockMvc.perform(
                patch(NOTIFICATIONS_URL + "/read-all")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        mockMvc.perform(
                get(NOTIFICATIONS_URL + "/unread-count")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void doctorPortalIsRestrictedToDoctorAccounts() throws Exception {

        TestFixture fixture = createFixture();

        mockMvc.perform(
                get("/api/v1/doctor/profile")
                        .with(customerJwt(fixture.patientUserId()))
        ).andExpect(status().isForbidden());

        mockMvc.perform(
                get("/api/v1/doctor/profile")
                        .with(adminJwt(fixture.adminUserId()))
        ).andExpect(status().isForbidden());

        mockMvc.perform(
                get("/api/v1/doctor/profile")
                        .with(doctorJwt(fixture.doctorUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId")
                        .value(fixture.doctorId().toString()))
                .andExpect(jsonPath("$.firstName").value("API"))
                .andExpect(jsonPath("$.lastName").value("TestDoctor"));

        // The customer-only appointment routes reject doctors.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(doctorJwt(fixture.doctorUserId()))
        ).andExpect(status().isForbidden());
    }

    @Test
    void patientCanUpdateOwnProfileAndDataStaysEncryptedAtRest()
            throws Exception {

        TestFixture fixture = createFixture();

        mockMvc.perform(
                put("/api/v1/patients/me")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Nimal",
                                  "lastName": "Perera",
                                  "phone": "0779999999",
                                  "nic": "200012345678",
                                  "dateOfBirth": "2000-01-01",
                                  "gender": "MALE"
                                }
                                """)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Nimal"))
                .andExpect(jsonPath("$.lastName").value("Perera"))
                .andExpect(jsonPath("$.phone").value("0779999999"));

        String storedFirstName = jdbcTemplate.queryForObject(
                """
                SELECT first_name_encrypted
                FROM patients
                WHERE id = ?
                """,
                String.class,
                fixture.patientId()
        );

        String storedPhone = jdbcTemplate.queryForObject(
                """
                SELECT phone_encrypted
                FROM patients
                WHERE id = ?
                """,
                String.class,
                fixture.patientId()
        );

        assertThat(storedFirstName)
                .isNotBlank()
                .doesNotContain("Nimal");
        assertThat(storedPhone)
                .isNotBlank()
                .doesNotContain("0779999999");

        mockMvc.perform(
                get("/api/v1/patients/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Nimal"))
                .andExpect(jsonPath("$.dateOfBirth").value("2000-01-01"));
    }

    // =========================================================
    // Helpers
    // =========================================================

    private String bookAppointment(UUID sessionId, UUID patientUserId)
            throws Exception {

        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(patientUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "sessionId", sessionId,
                                        "reason", "Integration test visit"
                                )
                        ))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);
        return response.get("appointmentId").asText();
    }

    private void recordPayment(String appointmentId, UUID patientUserId)
            throws Exception {

        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + appointmentId + "/payments")
                        .with(customerJwt(patientUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "CARD"}
                                """)
        ).andExpect(status().isCreated());
    }

    private void startConsultation(String appointmentId, UUID doctorUserId)
            throws Exception {

        mockMvc.perform(
                patch(DOCTOR_URL + "/" + appointmentId + "/start")
                        .with(doctorJwt(doctorUserId))
        ).andExpect(status().isOk());
    }

    private void completeConsultation(String appointmentId, UUID doctorUserId)
            throws Exception {

        mockMvc.perform(
                patch(DOCTOR_URL + "/" + appointmentId + "/complete")
                        .with(doctorJwt(doctorUserId))
        ).andExpect(status().isOk());
    }

    private String summaryJson(
            String symptoms,
            String diagnosis,
            String treatmentNotes,
            String prescription,
            String followUpInstructions,
            String followUpDate
    ) throws Exception {

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("symptoms", symptoms);
        body.put("diagnosis", diagnosis);
        body.put("treatmentNotes", treatmentNotes);
        body.put("prescription", prescription);
        body.put("followUpInstructions", followUpInstructions);
        body.put("followUpDate", followUpDate);

        return objectMapper.writeValueAsString(body);
    }

    private TestFixture createFixture() {

        String unique = UUID.randomUUID()
                .toString()
                .replace("-", "");

        Role doctorRole = roleRepository.findByName("DOCTOR")
                .orElseThrow();
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow();
        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow();

        User adminUser = new User(
                adminRole,
                "lifecycle.admin." + unique + "@example.test",
                "test-password-hash"
        );
        adminUser.activateAfterVerification();
        adminUser = userRepository.saveAndFlush(adminUser);

        User doctorUser = userRepository.saveAndFlush(
                new User(
                        doctorRole,
                        "lifecycle.doctor." + unique + "@example.test",
                        "test-password-hash"
                )
        );

        User secondDoctorUser = userRepository.saveAndFlush(
                new User(
                        doctorRole,
                        "lifecycle.doctor.second." + unique + "@example.test",
                        "test-password-hash"
                )
        );

        Specialisation specialisation = specialisationRepository
                .saveAndFlush(new Specialisation(
                        "Lifecycle Test " + unique,
                        "Temporary lifecycle test"
                ));

        Doctor doctor = doctorRepository.saveAndFlush(
                new Doctor(
                        doctorUser,
                        specialisation,
                        "API",
                        "TestDoctor",
                        "LIFECYCLE-" + unique,
                        "Test qualification",
                        "Temporary lifecycle integration test",
                        new BigDecimal("1500.00")
                )
        );

        Doctor secondDoctor = doctorRepository.saveAndFlush(
                new Doctor(
                        secondDoctorUser,
                        specialisation,
                        "API",
                        "SecondDoctor",
                        "LIFECYCLE-SECOND-" + unique,
                        "Test qualification",
                        "Temporary lifecycle integration test",
                        new BigDecimal("1500.00")
                )
        );

        User patientUser = new User(
                customerRole,
                "lifecycle.patient." + unique + "@example.test",
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

        User secondPatientUser = new User(
                customerRole,
                "lifecycle.patient.second." + unique + "@example.test",
                "test-password-hash"
        );
        secondPatientUser.activateAfterVerification();
        secondPatientUser = userRepository.saveAndFlush(secondPatientUser);

        Patient secondPatient = patientRepository.saveAndFlush(
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
                        "LIFECYCLE-" + unique.substring(0, 12),
                        "Lifecycle Test Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        8
                )
        );

        LocalDate sessionDate = LocalDate.now(
                ZoneId.of("Asia/Colombo")
        ).plusDays(30);

        DoctorSession firstSession = new DoctorSession(
                doctor,
                room,
                sessionDate,
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                ConsultationType.PHYSICAL,
                4
        );
        firstSession.updateSchedulingDetails(
                10,
                new BigDecimal("1500.00")
        );
        firstSession.open();
        firstSession = doctorSessionRepository.saveAndFlush(firstSession);

        DoctorSession secondSession = new DoctorSession(
                doctor,
                room,
                sessionDate.plusDays(1),
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                ConsultationType.PHYSICAL,
                4
        );
        secondSession.updateSchedulingDetails(
                10,
                new BigDecimal("1500.00")
        );
        secondSession.open();
        secondSession = doctorSessionRepository.saveAndFlush(secondSession);

        return new TestFixture(
                adminUser.getId(),
                doctorUser.getId(),
                doctor.getId(),
                secondDoctorUser.getId(),
                secondDoctor.getId(),
                patientUser.getId(),
                patient.getId(),
                secondPatientUser.getId(),
                secondPatient.getId(),
                firstSession.getId(),
                secondSession.getId()
        );
    }

    private Authentication authentication(UUID userId, String authority) {
        return new UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    private RequestPostProcessor customerJwt(UUID userId) {        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "CUSTOMER")
                        .claim("email", "lifecycle-customer@example.test")
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
                        .claim("email", "lifecycle-doctor@example.test")
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
                        .claim("email", "lifecycle-admin@example.test")
                )
                .authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                );
    }

    private record TestFixture(
            UUID adminUserId,
            UUID doctorUserId,
            UUID doctorId,
            UUID secondDoctorUserId,
            UUID secondDoctorId,
            UUID patientUserId,
            UUID patientId,
            UUID secondPatientUserId,
            UUID secondPatientId,
            UUID sessionId,
            UUID secondSessionId
    ) {
    }
}
