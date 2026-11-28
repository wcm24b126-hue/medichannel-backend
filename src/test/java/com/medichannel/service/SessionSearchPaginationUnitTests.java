package com.medichannel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.controller.DoctorSessionController;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.dto.session.DoctorSessionResponse;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.exception.GlobalExceptionHandler;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.AppointmentRepository.SessionActiveCount;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

public class SessionSearchPaginationUnitTests {

    private DoctorSessionRepository doctorSessionRepository;
    private DoctorRepository doctorRepository;
    private RoomRepository roomRepository;
    private AppointmentRepository appointmentRepository;
    private SessionQueueService sessionQueueService;
    private AuditLogService auditLogService;
    private NotificationService notificationService;
    private Clock clock;
    private UserRepository userRepository;
    private PatientDataProtectionService dataProtectionService;
    private DoctorSessionService doctorSessionService;
    private DoctorSessionController doctorSessionController;
    private MockMvc mockMvc;

    private Doctor doctorAlpha;
    private Doctor doctorBeta;
    private Specialisation cardiology;
    private Room room101;

    @BeforeEach
    void setUp() throws Exception {
        doctorSessionRepository = mock(DoctorSessionRepository.class);
        doctorRepository = mock(DoctorRepository.class);
        roomRepository = mock(RoomRepository.class);
        appointmentRepository = mock(AppointmentRepository.class);
        sessionQueueService = mock(SessionQueueService.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);
        clock = Clock.fixed(Instant.parse("2026-11-28T01:00:00Z"), ZoneId.of("Asia/Colombo"));
        userRepository = mock(UserRepository.class);
        dataProtectionService = mock(PatientDataProtectionService.class);

        doctorSessionService = new DoctorSessionService(
                doctorSessionRepository,
                doctorRepository,
                roomRepository,
                appointmentRepository,
                sessionQueueService,
                auditLogService,
                notificationService,
                clock,
                userRepository,
                dataProtectionService
        );
        doctorSessionController = new DoctorSessionController(doctorSessionService);
        mockMvc = MockMvcBuilders.standaloneSetup(doctorSessionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        Role docRole = new Role("DOCTOR", "Doctor");
        cardiology = new Specialisation("Cardiology", "Heart care");
        setField(cardiology, "id", UUID.randomUUID());

        User userA = new User(docRole, "alpha@hospital.lk", "hash");
        setField(userA, "id", UUID.randomUUID());
        doctorAlpha = new Doctor(
                userA, cardiology, "Amal", "Perera", "SLMC-1001", "MD",
                "0771111111", new BigDecimal("2500.00")
        );
        setField(doctorAlpha, "id", UUID.randomUUID());
        setField(doctorAlpha, "available", true);

        User userB = new User(docRole, "beta@hospital.lk", "hash");
        setField(userB, "id", UUID.randomUUID());
        doctorBeta = new Doctor(
                userB, cardiology, "Bimal", "Silva", "SLMC-1002", "MD",
                "0772222222", new BigDecimal("2500.00")
        );
        setField(doctorBeta, "id", UUID.randomUUID());
        setField(doctorBeta, "available", true);

        room101 = new Room("101", "Ground Floor", "East", RoomType.CONSULTATION, 30);
        setField(room101, "id", UUID.randomUUID());
    }

    private DoctorSession createSession(
            Doctor doctor,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            ConsultationType type,
            int maxAppointments,
            SessionStatus status
    ) throws Exception {
        DoctorSession session = new DoctorSession(
                doctor,
                type == ConsultationType.PHYSICAL ? room101 : null,
                date,
                start,
                end,
                type,
                maxAppointments
        );
        setField(session, "id", UUID.randomUUID());
        setField(session, "status", status);
        session.updateSchedulingDetails(ClinicSchedule.MINUTES_PER_PATIENT, new BigDecimal("2500.00"));
        return session;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    @Test
    @DisplayName("1. Default first page uses page=0, size=12")
    void testDefaultFirstPage() throws Exception {
        DoctorSession s1 = createSession(
                doctorAlpha,
                LocalDate.of(2026, 11, 29),
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL,
                20,
                SessionStatus.OPEN
        );
        Page<DoctorSession> page = new PageImpl<>(List.of(s1), PageRequest.of(0, 12), 25);
        when(doctorSessionRepository.findPublicOpenSessionsPage(
                eq(SessionStatus.OPEN), any(), any(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), any(), any(), eq(5), eq(30), any(), any(Pageable.class)
        )).thenReturn(page);
        SessionActiveCount activeCount = mock(SessionActiveCount.class);
        when(activeCount.getSessionId()).thenReturn(s1.getId());
        when(activeCount.getActiveCount()).thenReturn(5L);
        when(appointmentRepository.countActiveBySessionIds(any(), any()))
                .thenReturn(List.of(activeCount));

        mockMvc.perform(get("/api/v1/sessions/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(12))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content[0].remainingAppointments").value(15));
    }

    @Test
    @DisplayName("2. Custom page and size are forwarded to repository")
    void testCustomPageAndSize() throws Exception {
        Page<DoctorSession> empty = new PageImpl<>(List.of(), PageRequest.of(2, 6), 14);
        when(doctorSessionRepository.findPublicOpenSessionsPage(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(5), eq(30), any(), any(Pageable.class)
        )).thenReturn(empty);

        PagedResponse<DoctorSessionResponse> res = doctorSessionService.searchPublicSessions(
                null, null, null, null, null, null, null, null, 2, 6
        );

        assertThat(res.page()).isEqualTo(2);
        assertThat(res.size()).isEqualTo(6);
        assertThat(res.totalElements()).isEqualTo(14);
        assertThat(res.totalPages()).isEqualTo(3);
        assertThat(res.last()).isTrue();
    }

    @Test
    @DisplayName("3-5. Invalid page or size > 100 returns HTTP 400")
    void testInvalidPaginationRejected() throws Exception {
        assertThatThrownBy(() -> doctorSessionService.searchPublicSessions(
                null, null, null, null, null, null, null, null, -1, 12
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> doctorSessionService.searchPublicSessions(
                null, null, null, null, null, null, null, null, 0, 0
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> doctorSessionService.searchPublicSessions(
                null, null, null, null, null, null, null, null, 0, 101
        )).isInstanceOf(IllegalArgumentException.class);

        mockMvc.perform(get("/api/v1/sessions/search?page=-1&size=12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/sessions/search?page=0&size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/sessions/search?page=0&size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("6-7. Nearest session ordered first without overlap between pages")
    void testOrderingAndNoOverlap() throws Exception {
        DoctorSession s1 = createSession(
                doctorAlpha, LocalDate.of(2026, 11, 29),
                ClinicSchedule.MORNING_START, ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL, 20, SessionStatus.OPEN
        );
        DoctorSession s2 = createSession(
                doctorBeta, LocalDate.of(2026, 11, 30),
                ClinicSchedule.EVENING_START, ClinicSchedule.EVENING_END,
                ConsultationType.ONLINE, 20, SessionStatus.OPEN
        );

        when(doctorSessionRepository.findPublicOpenSessionsPage(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(5), eq(30), any(), eq(PageRequest.of(0, 1))
        )).thenReturn(new PageImpl<>(List.of(s1), PageRequest.of(0, 1), 2));

        when(doctorSessionRepository.findPublicOpenSessionsPage(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(5), eq(30), any(), eq(PageRequest.of(1, 1))
        )).thenReturn(new PageImpl<>(List.of(s2), PageRequest.of(1, 1), 2));

        PagedResponse<DoctorSessionResponse> p0 = doctorSessionService.searchPublicSessions(
                null, null, null, null, null, null, null, null, 0, 1
        );
        PagedResponse<DoctorSessionResponse> p1 = doctorSessionService.searchPublicSessions(
                null, null, null, null, null, null, null, null, 1, 1
        );

        assertThat(p0.content().get(0).sessionId()).isEqualTo(s1.getId());
        assertThat(p1.content().get(0).sessionId()).isEqualTo(s2.getId());
        assertThat(p0.content().get(0).sessionId()).isNotEqualTo(p1.content().get(0).sessionId());
    }

    @Test
    @DisplayName("8-9. Beyond-final page and empty result return valid metadata")
    void testBeyondFinalAndEmpty() {
        when(doctorSessionRepository.findPublicOpenSessionsPage(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(5), eq(30), any(), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 12), 0));

        PagedResponse<DoctorSessionResponse> res = doctorSessionService.searchPublicSessions(
                null, null, null, null, null, null, null, null, 5, 12
        );
        assertThat(res.content()).isEmpty();
        assertThat(res.totalElements()).isZero();
        assertThat(res.last()).isTrue();
    }

    @Test
    @DisplayName("10-14. Filters (doctor, specialisation, date range, type, period) apply before pagination")
    void testFiltersPassedBeforePagination() {
        UUID specId = cardiology.getId();
        UUID docId = doctorAlpha.getId();
        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2026, 12, 7);

        when(doctorSessionRepository.findPublicOpenSessionsPage(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(5), eq(30), any(), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 12), 0));

        doctorSessionService.searchPublicSessions(
                docId, specId, null, from, to, "Perera",
                ConsultationType.ONLINE, ClinicSchedule.EVENING_START, 0, 12
        );

        ArgumentCaptor<LocalTime> periodStartCaptor = ArgumentCaptor.forClass(LocalTime.class);
        ArgumentCaptor<LocalTime> periodEndCaptor = ArgumentCaptor.forClass(LocalTime.class);
        verify(doctorSessionRepository).findPublicOpenSessionsPage(
                eq(SessionStatus.OPEN),
                any(LocalDate.class),
                eq(ConsultationType.PHYSICAL),
                any(LocalTime.class),
                any(LocalTime.class),
                eq(docId),
                eq(specId),
                isNull(),
                eq("2026-12-01"),
                eq("2026-12-07"),
                eq("perera"),
                eq(ConsultationType.ONLINE),
                periodStartCaptor.capture(),
                periodEndCaptor.capture(),
                any(), any(), any(), any(), eq(5), eq(30), any(), eq(PageRequest.of(0, 12))
        );

        assertThat(periodStartCaptor.getValue()).isEqualTo(ClinicSchedule.EVENING_START);
        assertThat(periodEndCaptor.getValue()).isEqualTo(ClinicSchedule.EVENING_END);
    }

    @Test
    @DisplayName("15-17. Capacity accuracy and exact selected session retrieval")
    void testCapacityAccuracyAndExactSessionLookup() throws Exception {
        DoctorSession s1 = createSession(
                doctorAlpha, LocalDate.of(2026, 11, 29),
                ClinicSchedule.MORNING_START, ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL, 30, SessionStatus.OPEN
        );
        when(doctorSessionRepository.findById(s1.getId())).thenReturn(Optional.of(s1));
        when(appointmentRepository.countBySession_IdAndStatusIn(eq(s1.getId()), any())).thenReturn(29L);

        DoctorSessionResponse single = doctorSessionService.getSession(s1.getId());
        assertThat(single.sessionId()).isEqualTo(s1.getId());
        assertThat(single.maxAppointments()).isEqualTo(30);
        assertThat(single.remainingAppointments()).isEqualTo(1);
    }

    @Test
    @DisplayName("21. Response exposes no private account email or clinical information")
    void testNoPrivateDataInResponse() throws Exception {
        DoctorSession s1 = createSession(
                doctorAlpha, LocalDate.of(2026, 11, 29),
                ClinicSchedule.MORNING_START, ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL, 20, SessionStatus.OPEN
        );
        when(doctorSessionRepository.findPublicOpenSessionsPage(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(5), eq(30), any(), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(s1), PageRequest.of(0, 12), 1));

        String json = mockMvc.perform(get("/api/v1/sessions/search"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(json).doesNotContain("alpha@hospital.lk");
        assertThat(json).doesNotContain(doctorAlpha.getUser().getId().toString());
    }

    @Test
    @DisplayName("22. Backward compatibility GET /api/v1/sessions still returns List")
    void testLegacyArrayEndpointIntact() throws Exception {
        when(doctorSessionRepository.findPublicOpenSessions(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(5), eq(30), any()
        )).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
