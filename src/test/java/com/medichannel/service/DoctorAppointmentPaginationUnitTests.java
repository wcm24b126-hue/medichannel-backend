package com.medichannel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.medichannel.controller.DoctorPortalController;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.dto.doctor.DoctorAppointmentResponse;
import com.medichannel.dto.queue.SessionQueueResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.PaymentStatus;
import com.medichannel.exception.GlobalExceptionHandler;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.NotificationRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

class DoctorAppointmentPaginationUnitTests {

    private AppointmentRepository appointmentRepository;
    private DoctorSessionRepository doctorSessionRepository;
    private AppointmentService appointmentService;
    private PatientDataProtectionService dataProtectionService;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        doctorSessionRepository = mock(DoctorSessionRepository.class);
        dataProtectionService = mock(PatientDataProtectionService.class);
        when(dataProtectionService.decrypt(any())).thenReturn("Test Patient");

        appointmentService = new AppointmentService(
                appointmentRepository,
                doctorSessionRepository,
                mock(PatientRepository.class),
                mock(UserRepository.class),
                mock(NotificationRepository.class),
                dataProtectionService,
                mock(RoleRepository.class),
                mock(PasswordEncoder.class),
                mock(NotificationService.class),
                mock(AppointmentAccessPolicy.class),
                mock(SessionQueueService.class),
                mock(AuditLogService.class),
                Clock.fixed(Instant.parse("2026-11-27T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void test1_activeQueueReturnsEveryActionableAppointment() {
        UUID doctorUserId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        DoctorSession session = mockSession(sessionId, doctorUserId);
        when(doctorSessionRepository.findByIdAndDoctor_User_Id(sessionId, doctorUserId))
                .thenReturn(Optional.of(session));

        Appointment booked = sampleDoctorAppointment(UUID.randomUUID(), 1, AppointmentStatus.BOOKED);
        Appointment checkedIn = sampleDoctorAppointment(UUID.randomUUID(), 2, AppointmentStatus.CHECKED_IN);
        Appointment arrived = sampleDoctorAppointment(UUID.randomUUID(), 3, AppointmentStatus.ARRIVED);
        Appointment inConsult = sampleDoctorAppointment(UUID.randomUUID(), 4, AppointmentStatus.IN_CONSULTATION);
        Appointment completed = sampleDoctorAppointment(UUID.randomUUID(), 5, AppointmentStatus.COMPLETED);

        when(appointmentRepository.findBySession_IdAndDoctorHiddenAtIsNullOrderByAppointmentNumberAsc(sessionId))
                .thenReturn(List.of(booked, checkedIn, arrived, inConsult, completed));

        List<DoctorAppointmentResponse> active =
                appointmentService.getDoctorActiveAppointments(doctorUserId, sessionId);

        assertThat(active).hasSize(4);
        assertThat(active)
                .extracting(DoctorAppointmentResponse::status)
                .containsExactly(
                        AppointmentStatus.BOOKED,
                        AppointmentStatus.CHECKED_IN,
                        AppointmentStatus.ARRIVED,
                        AppointmentStatus.IN_CONSULTATION
                );
    }

    @Test
    void test2_activeQueueOrderingFollowsQueueNumberAscending() {
        UUID doctorUserId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        DoctorSession session = mockSession(sessionId, doctorUserId);
        when(doctorSessionRepository.findByIdAndDoctor_User_Id(sessionId, doctorUserId))
                .thenReturn(Optional.of(session));

        Appointment a1 = sampleDoctorAppointment(UUID.randomUUID(), 1, AppointmentStatus.BOOKED);
        Appointment a2 = sampleDoctorAppointment(UUID.randomUUID(), 2, AppointmentStatus.ARRIVED);
        Appointment a3 = sampleDoctorAppointment(UUID.randomUUID(), 5, AppointmentStatus.IN_CONSULTATION);

        when(appointmentRepository.findBySession_IdAndDoctorHiddenAtIsNullOrderByAppointmentNumberAsc(sessionId))
                .thenReturn(List.of(a1, a2, a3));

        List<DoctorAppointmentResponse> active =
                appointmentService.getDoctorActiveAppointments(doctorUserId, sessionId);

        assertThat(active)
                .extracting(DoctorAppointmentResponse::appointmentNumber)
                .containsExactly(1, 2, 5);
    }

    @Test
    void test3_historyIncludesOnlyClosedStatuses() {
        UUID doctorUserId = UUID.randomUUID();

        // When status is active (e.g. BOOKED), history returns empty
        PagedResponse<DoctorAppointmentResponse> activeStatus =
                appointmentService.getDoctorAppointmentHistory(
                        doctorUserId, 0, 20, AppointmentStatus.BOOKED, null
                );
        assertThat(activeStatus.content()).isEmpty();
        assertThat(activeStatus.totalElements()).isZero();

        // Captures closed statuses passed to repository
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AppointmentStatus>> statusesCaptor = ArgumentCaptor.forClass(Collection.class);

        when(appointmentRepository.findHistoryByDoctorUserId(
                eq(doctorUserId),
                statusesCaptor.capture(),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(Page.empty());

        appointmentService.getDoctorAppointmentHistory(doctorUserId, 0, 20, null, null);

        assertThat(statusesCaptor.getValue()).containsExactlyInAnyOrder(
                AppointmentStatus.COMPLETED,
                AppointmentStatus.CANCELLED,
                AppointmentStatus.NO_SHOW
        );
    }

    @Test
    void test4_historyPaginationDefaultsToPageZeroAndSizeTwenty() {
        UUID doctorUserId = UUID.randomUUID();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(appointmentRepository.findHistoryByDoctorUserId(
                eq(doctorUserId),
                any(),
                any(),
                any(),
                pageableCaptor.capture()
        )).thenReturn(Page.empty());

        appointmentService.getDoctorAppointmentHistory(doctorUserId, 0, 20, null, null);

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void test5_customPageAndSizeAppliedCorrectly() {
        UUID doctorUserId = UUID.randomUUID();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(appointmentRepository.findHistoryByDoctorUserId(
                eq(doctorUserId),
                any(),
                any(),
                any(),
                pageableCaptor.capture()
        )).thenReturn(Page.empty());

        appointmentService.getDoctorAppointmentHistory(doctorUserId, 3, 15, null, null);

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(15);
    }

    @Test
    void test6_invalidPageAndSizeRejectionThrowsException() {
        UUID doctorUserId = UUID.randomUUID();

        assertThatThrownBy(() ->
                appointmentService.getDoctorAppointmentHistory(doctorUserId, -1, 20, null, null)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("negative");

        assertThatThrownBy(() ->
                appointmentService.getDoctorAppointmentHistory(doctorUserId, 0, 0, null, null)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("greater than zero");
    }

    @Test
    void test7_maximumSizeEnforcementRejectsAbove100() {
        UUID doctorUserId = UUID.randomUUID();

        assertThatThrownBy(() ->
                appointmentService.getDoctorAppointmentHistory(doctorUserId, 0, 101, null, null)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("100");
    }

    @Test
    void test8_noOverlapBetweenPages() {
        UUID doctorUserId = UUID.randomUUID();
        Appointment a1 = sampleDoctorAppointment(UUID.randomUUID(), 1, AppointmentStatus.COMPLETED);
        Appointment a2 = sampleDoctorAppointment(UUID.randomUUID(), 2, AppointmentStatus.COMPLETED);

        PageRequest p0 = PageRequest.of(0, 1);
        PageRequest p1 = PageRequest.of(1, 1);

        when(appointmentRepository.findHistoryByDoctorUserId(
                eq(doctorUserId), any(), any(), any(), eq(p0)
        )).thenReturn(new PageImpl<>(List.of(a1), p0, 2));

        when(appointmentRepository.findHistoryByDoctorUserId(
                eq(doctorUserId), any(), any(), any(), eq(p1)
        )).thenReturn(new PageImpl<>(List.of(a2), p1, 2));

        PagedResponse<DoctorAppointmentResponse> page0 =
                appointmentService.getDoctorAppointmentHistory(doctorUserId, 0, 1, null, null);
        PagedResponse<DoctorAppointmentResponse> page1 =
                appointmentService.getDoctorAppointmentHistory(doctorUserId, 1, 1, null, null);

        assertThat(page0.content().getFirst().appointmentId())
                .isNotEqualTo(page1.content().getFirst().appointmentId());
    }

    @Test
    void test9_beyondFinalPageReturnsEmptyContentWithAccurateMetadata() {
        UUID doctorUserId = UUID.randomUUID();
        PageRequest beyond = PageRequest.of(5, 10);

        when(appointmentRepository.findHistoryByDoctorUserId(
                eq(doctorUserId), any(), any(), any(), eq(beyond)
        )).thenReturn(new PageImpl<>(List.of(), beyond, 15));

        PagedResponse<DoctorAppointmentResponse> resp =
                appointmentService.getDoctorAppointmentHistory(doctorUserId, 5, 10, null, null);

        assertThat(resp.content()).isEmpty();
        assertThat(resp.page()).isEqualTo(5);
        assertThat(resp.totalElements()).isEqualTo(15);
        assertThat(resp.totalPages()).isEqualTo(2);
        assertThat(resp.last()).isTrue();
    }

    @Test
    void test10_emptyHistoryReturnsCleanEmptyResponse() {
        UUID doctorUserId = UUID.randomUUID();
        PageRequest p0 = PageRequest.of(0, 20);

        when(appointmentRepository.findHistoryByDoctorUserId(
                eq(doctorUserId), any(), any(), any(), eq(p0)
        )).thenReturn(Page.empty(p0));

        PagedResponse<DoctorAppointmentResponse> resp =
                appointmentService.getDoctorAppointmentHistory(doctorUserId, 0, 20, null, null);

        assertThat(resp.content()).isEmpty();
        assertThat(resp.totalElements()).isZero();
        assertThat(resp.totalPages()).isZero();
        assertThat(resp.first()).isTrue();
        assertThat(resp.last()).isTrue();
    }

    @Test
    void test11_doctorACannotAccessDoctorBRecords() {
        UUID doctorA = UUID.randomUUID();
        UUID doctorB = UUID.randomUUID();

        // Repository query explicitly scopes by s.doctor.user.id = :userId
        ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
        when(appointmentRepository.findHistoryByDoctorUserId(
                userIdCaptor.capture(), any(), any(), any(), any(Pageable.class)
        )).thenReturn(Page.empty());

        appointmentService.getDoctorAppointmentHistory(doctorA, 0, 20, null, null);
        assertThat(userIdCaptor.getValue()).isEqualTo(doctorA);
        assertThat(userIdCaptor.getValue()).isNotEqualTo(doctorB);
    }

    @Test
    void test12_ownershipAppliesBeforePagination() throws Exception {
        Method historyMethod = AppointmentRepository.class.getMethod(
                "findHistoryByDoctorUserId",
                UUID.class,
                Collection.class,
                AppointmentStatus.class,
                LocalDate.class,
                Pageable.class
        );
        Query historyQuery = historyMethod.getAnnotation(Query.class);
        assertThat(historyQuery).isNotNull();
        assertThat(historyQuery.value())
                .contains("s.doctor.user.id = :userId")
                .contains("a.doctorHiddenAt IS NULL")
                .contains("ORDER BY s.sessionDate DESC, s.startTime DESC, a.id DESC");
        assertThat(historyQuery.countQuery())
                .contains("s.doctor.user.id = :userId")
                .contains("a.doctorHiddenAt IS NULL");
    }

    @Test
    void test13_sessionOwnershipCannotBeBypassed() {
        UUID doctorA = UUID.randomUUID();
        UUID unownedSessionId = UUID.randomUUID();

        when(doctorSessionRepository.findByIdAndDoctor_User_Id(unownedSessionId, doctorA))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                appointmentService.getDoctorActiveAppointments(doctorA, unownedSessionId)
        ).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void test14_hiddenPrivateClinicalDataIsNotAddedToListDtos() {
        for (RecordComponent component : DoctorAppointmentResponse.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            assertThat(name)
                    .doesNotContain("reason")
                    .doesNotContain("chat")
                    .doesNotContain("summary")
                    .doesNotContain("diagnosis")
                    .doesNotContain("treatment")
                    .doesNotContain("prescription");
        }
    }

    @Test
    void test15_activeQueueRemainsCompleteWithMoreThan20HistoryRecords() {
        UUID doctorUserId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        DoctorSession session = mockSession(sessionId, doctorUserId);
        when(doctorSessionRepository.findByIdAndDoctor_User_Id(sessionId, doctorUserId))
                .thenReturn(Optional.of(session));

        // Create 25 active appointments in the session
        List<Appointment> twentyFive = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> sampleDoctorAppointment(UUID.randomUUID(), i, AppointmentStatus.BOOKED))
                .toList();

        when(appointmentRepository.findBySession_IdAndDoctorHiddenAtIsNullOrderByAppointmentNumberAsc(sessionId))
                .thenReturn(twentyFive);

        List<DoctorAppointmentResponse> active =
                appointmentService.getDoctorActiveAppointments(doctorUserId, sessionId);

        // Entire active queue of 25 is returned completely without pagination or slicing
        assertThat(active).hasSize(25);
    }

    @Test
    void controllerExposesActiveAndPagedHistoryEndpoints() throws Exception {
        UUID doctorUserId = UUID.randomUUID();
        AppointmentService serviceMock = mock(AppointmentService.class);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(doctorUserId.toString())
                .build();

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DoctorPortalController(
                        mock(DoctorService.class),
                        serviceMock,
                        mock(DoctorSessionService.class)
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Object resolveArgument(
                            MethodParameter parameter,
                            ModelAndViewContainer mavContainer,
                            NativeWebRequest webRequest,
                            WebDataBinderFactory binderFactory
                    ) {
                        return jwt;
                    }
                })
                .build();

        when(serviceMock.getDoctorActiveAppointments(doctorUserId, null))
                .thenReturn(List.of());
        when(serviceMock.getDoctorAppointmentHistory(doctorUserId, 0, 20, null, null))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mvc.perform(get("/api/v1/doctor/appointments/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/v1/doctor/appointments/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content").isArray());

        // Test invalid pagination returns 400
        when(serviceMock.getDoctorAppointmentHistory(doctorUserId, -1, 20, null, null))
                .thenThrow(new IllegalArgumentException("Page index must not be negative."));

        mvc.perform(get("/api/v1/doctor/appointments/history").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    private DoctorSession mockSession(UUID sessionId, UUID doctorUserId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(doctorUserId);

        Doctor doctor = mock(Doctor.class);
        when(doctor.getUser()).thenReturn(user);

        DoctorSession session = mock(DoctorSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getDoctor()).thenReturn(doctor);
        return session;
    }

    private Appointment sampleDoctorAppointment(UUID id, int number, AppointmentStatus status) {
        Specialisation spec = mock(Specialisation.class);
        when(spec.getName()).thenReturn("Cardiology");

        Doctor doctor = mock(Doctor.class);
        when(doctor.getId()).thenReturn(UUID.randomUUID());
        when(doctor.getFirstName()).thenReturn("Doctor");
        when(doctor.getLastName()).thenReturn("Specialist");
        when(doctor.getSpecialisation()).thenReturn(spec);

        DoctorSession session = mock(DoctorSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getSessionDate()).thenReturn(LocalDate.of(2026, 11, 28));
        when(session.getStartTime()).thenReturn(LocalTime.of(8, 0));
        when(session.getMinutesPerPatient()).thenReturn(15);
        when(session.getFeeLkr()).thenReturn(new BigDecimal("3000.00"));
        when(session.getDoctor()).thenReturn(doctor);
        when(session.getConsultationType()).thenReturn(ConsultationType.PHYSICAL);

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstNameEncrypted()).thenReturn("enc_first");
        when(patient.getLastNameEncrypted()).thenReturn("enc_last");

        Appointment appt = mock(Appointment.class);
        when(appt.getId()).thenReturn(id);
        when(appt.getBookingReference()).thenReturn(UUID.randomUUID());
        when(appt.getSession()).thenReturn(session);
        when(appt.getPatient()).thenReturn(patient);
        when(appt.getAppointmentNumber()).thenReturn(number);
        when(appt.getStatus()).thenReturn(status);
        when(appt.getPaymentStatus()).thenReturn(PaymentStatus.PAID);
        when(appt.getCreatedAt()).thenReturn(Instant.parse("2026-11-28T07:30:00Z"));
        when(appt.getAcceptanceStatus()).thenReturn("ACCEPTED");
        return appt;
    }
}
