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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.medichannel.controller.AppointmentController;
import com.medichannel.dto.appointment.AppointmentResponse;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.PaymentStatus;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.NotificationRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

class PatientAppointmentPaginationUnitTests {

    private AppointmentRepository appointmentRepository;
    private UserRepository userRepository;
    private PatientRepository patientRepository;
    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        userRepository = mock(UserRepository.class);
        patientRepository = mock(PatientRepository.class);

        appointmentService = new AppointmentService(
                appointmentRepository,
                mock(DoctorSessionRepository.class),
                patientRepository,
                userRepository,
                mock(NotificationRepository.class),
                mock(PatientDataProtectionService.class),
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
    void getMyActiveAppointmentsReturnsOnlyActiveStatusesInAscendingSessionOrder() {
        UUID userId = UUID.randomUUID();
        stubEligiblePatient(userId);

        UUID oldBookedRef = UUID.randomUUID();
        Appointment todaysOldBooking = sampleAppointment(
                UUID.randomUUID(),
                oldBookedRef,
                1,
                LocalDate.of(2026, 11, 27),
                LocalTime.of(7, 0),
                AppointmentStatus.BOOKED,
                Instant.parse("2026-10-28T08:00:00Z")
        );

        when(appointmentRepository.findActiveByPatientUserId(eq(userId), any()))
                .thenReturn(List.of(todaysOldBooking));

        List<AppointmentResponse> active =
                appointmentService.getMyActiveAppointments(userId);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).bookingReference()).isEqualTo(oldBookedRef);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AppointmentStatus>> statusCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(appointmentRepository).findActiveByPatientUserId(
                eq(userId),
                statusCaptor.capture()
        );
        assertThat(statusCaptor.getValue()).containsExactlyInAnyOrder(
                AppointmentStatus.BOOKED,
                AppointmentStatus.CHECKED_IN,
                AppointmentStatus.ARRIVED,
                AppointmentStatus.IN_CONSULTATION
        );
    }

    @Test
    void getMyAppointmentHistoryReturnsPagedClosedHistoryWithExpectedMetadata() {
        UUID userId = UUID.randomUUID();
        stubEligiblePatient(userId);

        UUID completedRef = UUID.randomUUID();
        Appointment closedAppt = sampleAppointment(
                UUID.randomUUID(),
                completedRef,
                4,
                LocalDate.of(2026, 11, 20),
                LocalTime.of(13, 0),
                AppointmentStatus.COMPLETED,
                Instant.parse("2026-11-19T08:00:00Z")
        );

        Pageable requested = PageRequest.of(1, 5);
        when(appointmentRepository.findHistoryByPatientUserId(
                eq(userId),
                any(),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(closedAppt), requested, 12));

        PagedResponse<AppointmentResponse> result =
                appointmentService.getMyAppointmentHistory(userId, 1, 5);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(12);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).bookingReference()).isEqualTo(completedRef);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AppointmentStatus>> statusCaptor =
                ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);
        verify(appointmentRepository).findHistoryByPatientUserId(
                eq(userId),
                statusCaptor.capture(),
                pageableCaptor.capture()
        );
        assertThat(statusCaptor.getValue()).containsExactlyInAnyOrder(
                AppointmentStatus.COMPLETED,
                AppointmentStatus.CANCELLED,
                AppointmentStatus.NO_SHOW
        );
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void getMyAppointmentHistoryHandlesEmptyDatasetAndBeyondLastPageSafely() {
        UUID userId = UUID.randomUUID();
        stubEligiblePatient(userId);

        when(appointmentRepository.findHistoryByPatientUserId(
                eq(userId),
                any(),
                any(Pageable.class)
        )).thenReturn(Page.empty(PageRequest.of(0, 20)));

        PagedResponse<AppointmentResponse> empty =
                appointmentService.getMyAppointmentHistory(userId, 0, 20);
        assertThat(empty.content()).isEmpty();
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.first()).isTrue();
        assertThat(empty.last()).isTrue();

        when(appointmentRepository.findHistoryByPatientUserId(
                eq(userId),
                any(),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 12));

        PagedResponse<AppointmentResponse> beyondLast =
                appointmentService.getMyAppointmentHistory(userId, 5, 10);
        assertThat(beyondLast.content()).isEmpty();
        assertThat(beyondLast.page()).isEqualTo(5);
        assertThat(beyondLast.totalElements()).isEqualTo(12);
        assertThat(beyondLast.totalPages()).isEqualTo(2);
    }

    @Test
    void getMyAppointmentHistoryRejectsInvalidPaginationParameters() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> appointmentService.getMyAppointmentHistory(userId, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");

        assertThatThrownBy(() -> appointmentService.getMyAppointmentHistory(userId, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");

        assertThatThrownBy(() -> appointmentService.getMyAppointmentHistory(userId, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void repositoryQueriesEnforceOwnershipHiddenExclusionAndDeterministicOrder() throws Exception {
        Method activeMethod = AppointmentRepository.class.getMethod(
                "findActiveByPatientUserId",
                UUID.class,
                Collection.class
        );
        Query activeQuery = activeMethod.getAnnotation(Query.class);
        assertThat(activeQuery).isNotNull();
        assertThat(activeQuery.value())
                .contains("a.patient.user.id = :userId")
                .contains("a.patientHiddenAt IS NULL")
                .contains("a.status IN :statuses")
                .contains("ORDER BY s.sessionDate ASC, s.startTime ASC, a.id ASC");

        Method historyMethod = AppointmentRepository.class.getMethod(
                "findHistoryByPatientUserId",
                UUID.class,
                Collection.class,
                Pageable.class
        );
        Query historyQuery = historyMethod.getAnnotation(Query.class);
        assertThat(historyQuery).isNotNull();
        assertThat(historyQuery.value())
                .contains("a.patient.user.id = :userId")
                .contains("a.patientHiddenAt IS NULL")
                .contains("a.status IN :statuses")
                .contains("ORDER BY s.sessionDate DESC, s.startTime DESC, a.id DESC");
        assertThat(historyQuery.countQuery())
                .contains("a.patient.user.id = :userId")
                .contains("a.patientHiddenAt IS NULL")
                .contains("a.status IN :statuses");
    }

    @Test
    void controllerExposesCompatibilityActiveAndPagedHistoryEndpoints() throws Exception {
        UUID userId = UUID.randomUUID();
        AppointmentService serviceMock = mock(AppointmentService.class);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .build();

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AppointmentController(serviceMock))
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

        when(serviceMock.getMyAppointments(userId)).thenReturn(List.of());
        when(serviceMock.getMyActiveAppointments(userId)).thenReturn(List.of());
        when(serviceMock.getMyAppointmentHistory(userId, 0, 20))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mvc.perform(get("/api/v1/appointments/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/v1/appointments/me/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/v1/appointments/me/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content").isArray());

        verify(serviceMock).getMyAppointmentHistory(userId, 0, 20);
    }

    private void stubEligiblePatient(UUID userId) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn("CUSTOMER");

        User user = mock(User.class);
        when(user.getRole()).thenReturn(role);
        when(user.getAccountStatus()).thenReturn("ACTIVE");
        when(user.isEmailVerified()).thenReturn(true);

        Patient patient = mock(Patient.class);
        when(userRepository.findByIdWithRole(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    private Appointment sampleAppointment(
            UUID id,
            UUID ref,
            int number,
            LocalDate sessionDate,
            LocalTime startTime,
            AppointmentStatus status,
            Instant createdAt
    ) {
        Specialisation spec = mock(Specialisation.class);
        when(spec.getName()).thenReturn("Cardiology");

        Doctor doctor = mock(Doctor.class);
        when(doctor.getId()).thenReturn(UUID.randomUUID());
        when(doctor.getFirstName()).thenReturn("Nimal");
        when(doctor.getLastName()).thenReturn("Perera");
        when(doctor.getSpecialisation()).thenReturn(spec);

        DoctorSession session = mock(DoctorSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getSessionDate()).thenReturn(sessionDate);
        when(session.getStartTime()).thenReturn(startTime);
        when(session.getMinutesPerPatient()).thenReturn(15);
        when(session.getFeeLkr()).thenReturn(new BigDecimal("2500.00"));
        when(session.getDoctor()).thenReturn(doctor);
        when(session.getConsultationType()).thenReturn(ConsultationType.PHYSICAL);
        when(session.getRoom()).thenReturn(null);

        Appointment appt = mock(Appointment.class);
        when(appt.getId()).thenReturn(id);
        when(appt.getBookingReference()).thenReturn(ref);
        when(appt.getSession()).thenReturn(session);
        when(appt.getAppointmentNumber()).thenReturn(number);
        when(appt.getStatus()).thenReturn(status);
        when(appt.getPaymentStatus()).thenReturn(PaymentStatus.PENDING);
        when(appt.getCreatedAt()).thenReturn(createdAt);
        when(appt.getAcceptanceStatus()).thenReturn("PENDING");
        return appt;
    }
}
