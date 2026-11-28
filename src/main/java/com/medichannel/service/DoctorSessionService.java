package com.medichannel.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.dto.common.PagedResponse;
import com.medichannel.dto.session.DoctorSessionRequest;
import com.medichannel.dto.session.DoctorSessionResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Room;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.NotificationType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

@Service
public class DoctorSessionService {

    private static final List<AppointmentStatus> ACTIVE_APPOINTMENT_STATUSES =
            List.of(
                    AppointmentStatus.BOOKED,
                    AppointmentStatus.CHECKED_IN,
                    AppointmentStatus.ARRIVED,
                    AppointmentStatus.IN_CONSULTATION
            );

    private final DoctorSessionRepository doctorSessionRepository;
    private final DoctorRepository doctorRepository;
    private final RoomRepository roomRepository;
    private final AppointmentRepository appointmentRepository;
    private final SessionQueueService sessionQueueService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final UserRepository userRepository;
    private final PatientDataProtectionService dataProtectionService;

    public DoctorSessionService(
            DoctorSessionRepository doctorSessionRepository,
            DoctorRepository doctorRepository,
            RoomRepository roomRepository,
            AppointmentRepository appointmentRepository,
            SessionQueueService sessionQueueService,
            AuditLogService auditLogService,
            NotificationService notificationService,
            Clock clock,
            UserRepository userRepository,
            PatientDataProtectionService dataProtectionService
    ) {
        this.doctorSessionRepository = doctorSessionRepository;
        this.doctorRepository = doctorRepository;
        this.roomRepository = roomRepository;
        this.appointmentRepository = appointmentRepository;
        this.sessionQueueService = sessionQueueService;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.clock = clock;
        this.userRepository = userRepository;
        this.dataProtectionService = dataProtectionService;
    }

    // Audit identity comes from the validated token subject, not the email
    // claim, which is no longer authoritative.
    private String getActorEmail() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                String subject = null;

                if (auth.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                    subject = jwt.getSubject();
                } else if (auth.getName() != null && !auth.getName().isBlank()) {
                    subject = auth.getName();
                }

                if (subject != null) {
                    UUID userId = UUID.fromString(subject);
                    return userRepository.findById(userId)
                            .map(user -> user.getEmail())
                            .orElse(subject);
                }
            }
        } catch (Exception ignored) {
        }
        return "manager@medichannel.demo";
    }

    // Create a new doctor session.
    @Transactional
    public DoctorSessionResponse createSession(
            DoctorSessionRequest request
    ) {
        validateRequest(request);
        validateDailyCapacity(
                request.doctorId(),
                request.sessionDate(),
                request.maxAppointments(),
                null
        );

        Doctor doctor = doctorRepository
                .findPublicDoctorById(request.doctorId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "An eligible doctor was not found."
                ));

        Room room = resolveRoom(
                request.roomId(),
                request.consultationType()
        );

        DoctorSession session = new DoctorSession(
                doctor,
                room,
                request.sessionDate(),
                request.startTime(),
                request.endTime(),
                request.consultationType(),
                request.maxAppointments()
        );

        session.updateSchedulingDetails(
                request.minutesPerPatient(),
                request.feeLkr()
        );
        session.open();

        try {
            DoctorSession savedSession =
                    doctorSessionRepository.saveAndFlush(session);

            auditLogService.log(
                    getActorEmail(),
                    "MANAGER",
                    "Session created",
                    "Session scheduled for Dr. " + doctor.getFirstName() + " " + doctor.getLastName()
                            + " on " + request.sessionDate() + " (" + request.startTime() + " - " + request.endTime() + ")",
                    "10.0.4.18"
            );

            try {
                notificationService.create(
                        doctor.getUser().getId(),
                        NotificationType.ACCOUNT_STATUS_CHANGED,
                        "New session scheduled",
                        "A " + request.consultationType() + " consultation session has been scheduled for you on "
                                + request.sessionDate() + " (" + request.startTime() + " - " + request.endTime() + ").",
                        null
                );
            } catch (Exception ignored) {
            }

            return toResponse(savedSession);

        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, "23P01")) {
                throw new ConflictException(
                        "The doctor or room already has an overlapping session."
                );
            }

            throw exception;
        }
    }

    // Update an existing scheduled or open session.
    @Transactional
    public DoctorSessionResponse updateSession(
            UUID sessionId,
            DoctorSessionRequest request
    ) {
        validateRequest(request);

        DoctorSession session = doctorSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor session was not found."
                ));

        if (session.getStatus() == SessionStatus.CANCELLED
                || session.getStatus() == SessionStatus.COMPLETED) {
            throw new ConflictException(
                    "Cancelled or completed sessions cannot be edited."
            );
        }

        if (request.maxAppointments()
                < session.getLastAppointmentNumber()) {
            throw new ConflictException(
                    "Maximum appointments cannot be lower than the number already booked."
            );
        }

        validateDailyCapacity(
                request.doctorId(),
                request.sessionDate(),
                request.maxAppointments(),
                sessionId
        );

        Doctor doctor = doctorRepository
                .findPublicDoctorById(request.doctorId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "An eligible doctor was not found."
                ));

        Room room = resolveRoom(
                request.roomId(),
                request.consultationType()
        );

        session.updateSchedule(
                doctor,
                room,
                request.sessionDate(),
                request.startTime(),
                request.endTime(),
                request.consultationType(),
                request.maxAppointments()
        );

        session.updateSchedulingDetails(
                request.minutesPerPatient(),
                request.feeLkr()
        );

        try {
            return toResponse(
                    doctorSessionRepository.saveAndFlush(session)
            );
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, "23P01")) {
                throw new ConflictException(
                        "The doctor or room already has an overlapping session."
                );
            }

            throw exception;
        }
    }

    @Transactional
    public DoctorSessionResponse openSession(
            UUID sessionId,
            UUID actorUserId,
            String actorRole
    ) {
        DoctorSession session = requireActorCanManage(
                sessionId,
                actorUserId,
                actorRole
        );
        applyTransition(() -> session.open());
        publishQueue(session);
        return toResponse(session);
    }

    @Transactional
    public DoctorSessionResponse startSession(
            UUID sessionId,
            UUID actorUserId,
            String actorRole
    ) {
        DoctorSession session = requireActorCanManage(
                sessionId,
                actorUserId,
                actorRole
        );
        applyTransition(() -> session.start());
        publishQueue(session);
        return toResponse(session);
    }

    @Transactional
    public DoctorSessionResponse completeSession(
            UUID sessionId,
            UUID actorUserId,
            String actorRole
    ) {
        DoctorSession session = requireActorCanManage(
                sessionId,
                actorUserId,
                actorRole
        );
        applyTransition(() -> session.complete());
        publishQueue(session);
        return toResponse(session);
    }

    @Transactional
    public DoctorSessionResponse cancelSession(
            UUID sessionId,
            UUID actorUserId,
            String actorRole,
            String reason
    ) {
        DoctorSession session = requireActorCanManage(
                sessionId,
                actorUserId,
                actorRole
        );
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isBlank()) {
            throw new IllegalArgumentException(
                    "A cancellation reason is required."
            );
        }
        if (trimmedReason.length() > 500) {
            trimmedReason = trimmedReason.substring(0, 500);
        }
        String encryptedReason = dataProtectionService.encrypt(trimmedReason);
        applyTransition(() -> session.cancel());

        // BR-11/3.3: cancelling a session cancels every appointment in
        // it. Records are retained (status changes, never deleted) and
        // each affected patient is notified.
        String when = session.getSessionDate() + " at " + session.getStartTime();
        int cancelledAppointments = 0;

        for (Appointment appointment : appointmentRepository
                .findBySession_IdOrderByAppointmentNumberAsc(sessionId)) {
            if (appointment.getStatus() == AppointmentStatus.BOOKED
                    || appointment.getStatus() == AppointmentStatus.CHECKED_IN
                    || appointment.getStatus() == AppointmentStatus.ARRIVED
                    || appointment.getStatus() == AppointmentStatus.IN_CONSULTATION) {
                appointment.cancelByStaff(clock.instant(), encryptedReason);
                appointmentRepository.save(appointment);
                cancelledAppointments++;

                notificationService.create(
                        appointment.getPatient().getUser().getId(),
                        NotificationType.APPOINTMENT_CANCELLED,
                        "Session cancelled",
                        "Your session with Dr. "
                                + session.getDoctor().getFirstName() + " "
                                + session.getDoctor().getLastName()
                                + " on " + when
                                + " has been cancelled. Appointment #"
                                + appointment.getAppointmentNumber()
                                + " is cancelled and remains in your history.",
                        appointment.getId()
                );
            }
        }

        appointmentRepository.flush();
        publishQueue(session);
        auditLogService.log(
                getActorEmail(),
                "MANAGER",
                "Session cancelled",
                "Session for Dr. " + session.getDoctor().getFirstName() + " " + session.getDoctor().getLastName()
                        + " on " + session.getSessionDate() + " was cancelled ("
                        + cancelledAppointments + " appointments cancelled).",
                "10.0.4.18"
        );
        return toResponse(session);
    }

    @Transactional
    public DoctorSessionResponse updateDelay(
            UUID sessionId,
            int delayMinutes,
            UUID actorUserId,
            String actorRole
    ) {
        DoctorSession session = requireActorCanManage(
                sessionId,
                actorUserId,
                actorRole
        );
        session.updateDelay(delayMinutes);
        publishQueue(session);

        // RT-04: a delay recalculates estimated times and must notify
        // every patient still waiting in the session.
        for (Appointment appointment : appointmentRepository
                .findBySession_IdOrderByAppointmentNumberAsc(sessionId)) {
            if (appointment.getStatus() == AppointmentStatus.BOOKED
                    || appointment.getStatus() == AppointmentStatus.CHECKED_IN
                    || appointment.getStatus() == AppointmentStatus.ARRIVED) {
                LocalDateTime estimate = LocalDateTime.of(
                        session.getSessionDate(),
                        session.getStartTime()
                ).plusMinutes(
                        (long) (appointment.getAppointmentNumber() - 1)
                                * session.getMinutesPerPatient()
                                + delayMinutes
                );

                notificationService.create(
                        appointment.getPatient().getUser().getId(),
                        NotificationType.APPOINTMENT_REMINDER,
                        "Session delayed",
                        "Your session on " + session.getSessionDate()
                                + " is delayed by " + delayMinutes
                                + " minutes. Your new estimated time is "
                                + estimate.toLocalTime() + ".",
                        appointment.getId()
                );
            }
        }

        auditLogService.log(
                getActorEmail(),
                "MANAGER",
                "Session delayed",
                "Session for Dr. " + session.getDoctor().getFirstName() + " " + session.getDoctor().getLastName()
                        + " delayed by " + delayMinutes + " minutes.",
                "10.0.4.18"
        );
        return toResponse(session);
    }

    // Doctor portal: the authenticated doctor's own sessions.
    @Transactional(readOnly = true)
    public List<DoctorSessionResponse> getDoctorSessions(
            UUID doctorUserId,
            LocalDate sessionDate
    ) {
        List<DoctorSession> sessions = sessionDate == null
                ? doctorSessionRepository
                        .findByDoctor_User_IdOrderBySessionDateDescStartTimeDesc(
                                doctorUserId
                        )
                : doctorSessionRepository
                        .findByDoctor_User_IdAndSessionDateOrderByStartTimeAsc(
                                doctorUserId,
                                sessionDate
                        );

        Map<UUID, Long> activeCounts = activeCountsBySessionId(
                sessions.stream().map(DoctorSession::getId).toList()
        );

        return sessions.stream()
                .map(session -> toResponse(
                        session,
                        activeCounts.getOrDefault(session.getId(), 0L)
                ))
                .toList();
    }

    // Public discovery of future sessions that still have capacity.
    @Transactional(readOnly = true)
    public List<DoctorSessionResponse> getPublicSessions(
            UUID doctorId,
            UUID specialisationId,
            LocalDate sessionDate,
            LocalDate fromDate,
            LocalDate toDate,
            String doctorName,
            ConsultationType consultationType,
            LocalTime period
    ) {
        LocalDateTime now = LocalDateTime.now(clock);

        // A week/month view sends an inclusive range. Keep the ordering sane
        // even when a client supplies the dates the other way around.
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            LocalDate earlier = fromDate;
            fromDate = toDate;
            toDate = earlier;
        }

        String query = doctorName == null
                ? null
                : doctorName.trim().toLowerCase(Locale.ROOT);
        LocalTime periodStart = period == null
                ? LocalTime.MIDNIGHT
                : period;
        LocalTime periodEnd = ClinicSchedule.periodEnd(period);

        List<DoctorSession> sessions = doctorSessionRepository.findPublicOpenSessions(
                SessionStatus.OPEN,
                now.toLocalDate(),
                ConsultationType.PHYSICAL,
                ClinicSchedule.bookingCutoff(
                        now.toLocalTime(),
                        ConsultationType.PHYSICAL
                ),
                ClinicSchedule.bookingCutoff(
                        now.toLocalTime(),
                        ConsultationType.ONLINE
                ),
                doctorId,
                specialisationId,
                sessionDate == null ? null : sessionDate.toString(),
                fromDate == null ? null : fromDate.toString(),
                toDate == null ? null : toDate.toString(),
                query == null || query.isEmpty() ? null : query,
                consultationType,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ClinicSchedule.EVENING_START,
                ClinicSchedule.EVENING_END,
                ClinicSchedule.MINUTES_PER_PATIENT,
                ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION,
                ACTIVE_APPOINTMENT_STATUSES
        );

        // One grouped count for the whole result page instead of one
        // appointment query per session. A week view returns hundreds of
        // sessions and the per-row count used to exceed Heroku's request
        // timeout.
        Map<UUID, Long> activeCounts = activeCountsBySessionId(
                sessions.stream().map(DoctorSession::getId).toList()
        );

        return sessions.stream()
                .filter(session -> session.getStartTime().isBefore(periodEnd)
                        && session.getEndTime().isAfter(periodStart))
                .map(session -> toResponse(
                        session,
                        activeCounts.getOrDefault(session.getId(), 0L)
                ))
                .toList();
    }

    // Server-side paginated public discovery of future sessions with capacity.
    @Transactional(readOnly = true)
    public PagedResponse<DoctorSessionResponse> searchPublicSessions(
            UUID doctorId,
            UUID specialisationId,
            LocalDate sessionDate,
            LocalDate fromDate,
            LocalDate toDate,
            String doctorName,
            ConsultationType consultationType,
            LocalTime period,
            int page,
            int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative.");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero.");
        }
        if (size > 100) {
            throw new IllegalArgumentException("Page size must not exceed 100.");
        }

        LocalDateTime now = LocalDateTime.now(clock);

        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            LocalDate earlier = fromDate;
            fromDate = toDate;
            toDate = earlier;
        }

        String query = doctorName == null
                ? null
                : doctorName.trim().toLowerCase(Locale.ROOT);
        LocalTime periodStart = period == null ? null : period;
        LocalTime periodEnd = period == null ? null : ClinicSchedule.periodEnd(period);
        Pageable pageable = PageRequest.of(page, size);

        Page<DoctorSession> sessionsPage = doctorSessionRepository.findPublicOpenSessionsPage(
                SessionStatus.OPEN,
                now.toLocalDate(),
                ConsultationType.PHYSICAL,
                ClinicSchedule.bookingCutoff(
                        now.toLocalTime(),
                        ConsultationType.PHYSICAL
                ),
                ClinicSchedule.bookingCutoff(
                        now.toLocalTime(),
                        ConsultationType.ONLINE
                ),
                doctorId,
                specialisationId,
                sessionDate == null ? null : sessionDate.toString(),
                fromDate == null ? null : fromDate.toString(),
                toDate == null ? null : toDate.toString(),
                query == null || query.isEmpty() ? null : query,
                consultationType,
                periodStart,
                periodEnd,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ClinicSchedule.EVENING_START,
                ClinicSchedule.EVENING_END,
                ClinicSchedule.MINUTES_PER_PATIENT,
                ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION,
                ACTIVE_APPOINTMENT_STATUSES,
                pageable
        );

        Map<UUID, Long> activeCounts = activeCountsBySessionId(
                sessionsPage.getContent().stream().map(DoctorSession::getId).toList()
        );

        return PagedResponse.from(
                sessionsPage.map(session -> toResponse(
                        session,
                        activeCounts.getOrDefault(session.getId(), 0L)
                ))
        );
    }

    // Get the details of one session.
    @Transactional(readOnly = true)
    public DoctorSessionResponse getSession(UUID sessionId) {

        DoctorSession session = doctorSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor session was not found."
                ));

        return toResponse(session);
    }

    private DoctorSession findSession(UUID sessionId) {
        return doctorSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor session was not found."
                ));
    }

    /**
     * Managers and administrators may manage every session. A doctor
     * may only change the state of a session assigned to them, which
     * closes the IDOR hole on the session transition endpoints.
     */
    private DoctorSession requireActorCanManage(
            UUID sessionId,
            UUID actorUserId,
            String actorRole
    ) {
        DoctorSession session = findSession(sessionId);

        if ("DOCTOR".equalsIgnoreCase(actorRole)) {
            boolean ownSession = session.getDoctor() != null
                    && session.getDoctor().getUser() != null
                    && session.getDoctor().getUser().getId()
                            .equals(actorUserId);

            if (!ownSession) {
                throw new ForbiddenOperationException(
                        "Only the assigned doctor can manage this session."
                );
            }
        }

        return session;
    }

    private void publishQueue(DoctorSession session) {
        sessionQueueService.broadcast(
                sessionQueueService.buildSummary(session)
        );
    }

    private void applyTransition(Runnable transition) {
        try {
            transition.run();
        } catch (IllegalStateException exception) {
            throw new ConflictException(exception.getMessage());
        }
    }

    private void validateDailyCapacity(
            UUID doctorId,
            LocalDate sessionDate,
            int requestedMaxAppointments,
            UUID excludedSessionId
    ) {
        long alreadyScheduled = doctorSessionRepository
                .sumActiveMaxAppointmentsByDoctorAndDate(
                        doctorId,
                        sessionDate,
                        SessionStatus.CANCELLED,
                        excludedSessionId
                );

        if (alreadyScheduled + requestedMaxAppointments
                > ClinicSchedule.MAX_APPOINTMENTS_PER_DAY) {
            throw new ConflictException(
                    "Adding this session would exceed the daily limit of "
                            + ClinicSchedule.MAX_APPOINTMENTS_PER_DAY
                            + " patients for this doctor."
            );
        }
    }

    private void validateRequest(DoctorSessionRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Session information is required."
            );
        }

        if (request.doctorId() == null
                || request.sessionDate() == null
                || request.startTime() == null
                || request.endTime() == null
                || request.consultationType() == null
                || request.feeLkr() == null) {
            throw new IllegalArgumentException(
                    "Required session information is missing."
            );
        }

        if (!request.endTime().isAfter(request.startTime())) {
            throw new IllegalArgumentException(
                    "Session end time must be after its start time."
            );
        }

        if (!ClinicSchedule.isAllowedWindow(
                request.startTime(),
                request.endTime()
        )) {
            throw new IllegalArgumentException(
                    "Sessions must use one of the published windows: "
                            + ClinicSchedule.allowedWindowLabel() + "."
            );
        }

        if (request.minutesPerPatient() != ClinicSchedule.MINUTES_PER_PATIENT) {
            throw new IllegalArgumentException(
                    "Each patient must be allocated exactly "
                            + ClinicSchedule.MINUTES_PER_PATIENT + " minutes."
            );
        }

        LocalDateTime sessionEnd = LocalDateTime.of(
                request.sessionDate(),
                request.endTime()
        );

        if (sessionEnd.isBefore(LocalDateTime.now(clock))) {
            throw new IllegalArgumentException(
                    "A session cannot be scheduled in the past."
            );
        }

        if (request.maxAppointments() < 1
                || request.maxAppointments() > ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION) {
            throw new IllegalArgumentException(
                    "Maximum appointments must be between 1 and "
                            + ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION
                            + " per session."
            );
        }

        /*
         * The two published windows start at 07:00 and 13:00. A full 30-patient
         * evening session can run past 15:00, but the hospital-day envelope
         * stays open until 16:00. Validate the last five-minute estimate
         * against that envelope rather than against the shorter booking window.
         */
        LocalTime lastEstimateStart = request.startTime().plusMinutes(
                (long) (request.maxAppointments() - 1)
                        * request.minutesPerPatient()
        );

        if (lastEstimateStart.plusMinutes(request.minutesPerPatient())
                .isAfter(ClinicSchedule.END_TIME)) {
            throw new IllegalArgumentException(
                    "The hospital day is too short for the requested number "
                            + "of appointments."
            );
        }

        if (request.feeLkr().compareTo(BigDecimal.ZERO) < 0
                || request.feeLkr().scale() > 2
                || request.feeLkr().precision() > 10) {
            throw new IllegalArgumentException(
                    "The consultation fee must be a valid LKR amount with up to two decimal places."
            );
        }
    }

    private Room resolveRoom(
            UUID roomId,
            ConsultationType consultationType
    ) {
        if (consultationType == ConsultationType.ONLINE) {
            if (roomId != null) {
                throw new IllegalArgumentException(
                        "Online sessions must not be assigned a physical room."
                );
            }

            return null;
        }

        if (roomId == null) {
            throw new IllegalArgumentException(
                    "Physical sessions require a room."
            );
        }

        Room room = roomRepository
                .findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The selected room was not found."
                ));

        if (!room.isActive()) {
            throw new ConflictException(
                    "The selected room is not active."
            );
        }

        return room;
    }

    private Map<UUID, Long> activeCountsBySessionId(
            Collection<UUID> sessionIds
    ) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }

        return appointmentRepository
                .countActiveBySessionIds(
                        sessionIds,
                        ACTIVE_APPOINTMENT_STATUSES
                )
                .stream()
                .collect(Collectors.toMap(
                        AppointmentRepository.SessionActiveCount::getSessionId,
                        AppointmentRepository.SessionActiveCount::getActiveCount
                ));
    }

    private DoctorSessionResponse toResponse(DoctorSession session) {
        long activeCount = appointmentRepository.countBySession_IdAndStatusIn(
                session.getId(),
                ACTIVE_APPOINTMENT_STATUSES
        );

        return toResponse(session, activeCount);
    }

    private DoctorSessionResponse toResponse(
            DoctorSession session,
            long activeAppointments
    ) {

        Doctor doctor = session.getDoctor();
        Room room = session.getRoom();

        return new DoctorSessionResponse(
                session.getId(),
                doctor.getId(),
                doctor.getFirstName() + " " + doctor.getLastName(),
                doctor.isAvailable(),
                doctor.getSpecialisation().getName(),
                room != null ? room.getId() : null,
                room != null ? room.getRoomNumber() : null,
                session.getSessionDate(),
                session.getStartTime(),
                session.getEndTime(),
                session.getConsultationType(),
                session.getMaxAppointments(),
                Math.max(
                        0,
                        session.getMaxAppointments() - (int) activeAppointments
                ),
                session.getMinutesPerPatient(),
                session.getFeeLkr(),
                session.getDelayMinutes(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    // Identify PostgreSQL's session-overlap error without masking other errors.
    private boolean hasSqlState(
            Throwable exception,
            String expectedSqlState
    ) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof SQLException sqlException
                    && expectedSqlState.equals(sqlException.getSQLState())) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
