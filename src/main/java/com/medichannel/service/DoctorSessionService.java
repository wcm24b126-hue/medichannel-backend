package com.medichannel.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

@Service
public class DoctorSessionService {

    private final DoctorSessionRepository doctorSessionRepository;
    private final DoctorRepository doctorRepository;
    private final RoomRepository roomRepository;
    private final AppointmentRepository appointmentRepository;
    private final SessionQueueService sessionQueueService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final Clock clock;

    public DoctorSessionService(
            DoctorSessionRepository doctorSessionRepository,
            DoctorRepository doctorRepository,
            RoomRepository roomRepository,
            AppointmentRepository appointmentRepository,
            SessionQueueService sessionQueueService,
            AuditLogService auditLogService,
            NotificationService notificationService,
            Clock clock
    ) {
        this.doctorSessionRepository = doctorSessionRepository;
        this.doctorRepository = doctorRepository;
        this.roomRepository = roomRepository;
        this.appointmentRepository = appointmentRepository;
        this.sessionQueueService = sessionQueueService;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    private String getActorEmail() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                if (auth.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                    String email = jwt.getClaimAsString("email");
                    if (email != null && !email.isBlank()) {
                        return email;
                    }
                }
                if (auth.getName() != null && !auth.getName().isBlank() && auth.getName().contains("@")) {
                    return auth.getName();
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
            String actorRole
    ) {
        DoctorSession session = requireActorCanManage(
                sessionId,
                actorUserId,
                actorRole
        );
        applyTransition(() -> session.cancel());

        // BR-11/3.3: cancelling a session cancels every appointment in
        // it. Records are retained (status changes, never deleted) and
        // each affected patient is notified.
        String when = session.getSessionDate() + " at " + session.getStartTime();
        int cancelledAppointments = 0;

        for (Appointment appointment : appointmentRepository
                .findBySession_IdOrderByAppointmentNumberAsc(sessionId)) {
            if (appointment.getStatus() == AppointmentStatus.BOOKED
                    || appointment.getStatus() == AppointmentStatus.ARRIVED
                    || appointment.getStatus() == AppointmentStatus.IN_CONSULTATION) {
                appointment.cancelByStaff(clock.instant());
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

        return sessions.stream().map(this::toResponse).toList();
    }

    // Public discovery of future sessions that still have capacity.
    @Transactional(readOnly = true)
    public List<DoctorSessionResponse> getPublicSessions(
            UUID doctorId,
            UUID specialisationId,
            LocalDate sessionDate,
            String doctorName,
            ConsultationType consultationType
    ) {
        LocalDateTime now = LocalDateTime.now(clock);

        String query = doctorName == null
                ? null
                : doctorName.trim().toLowerCase(Locale.ROOT);

        return doctorSessionRepository.findPublicOpenSessions(
                SessionStatus.OPEN,
                now.toLocalDate(),
                now.toLocalTime(),
                doctorId,
                specialisationId,
                sessionDate == null ? null : sessionDate.toString(),
                query == null || query.isEmpty() ? null : query,
                consultationType
        ).stream().map(this::toResponse).toList();
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

        LocalDateTime sessionStart = LocalDateTime.of(
                request.sessionDate(),
                request.startTime()
        );

        if (sessionStart.isBefore(LocalDateTime.now(clock))) {
            throw new IllegalArgumentException(
                    "A session cannot be scheduled in the past."
            );
        }

        if (request.maxAppointments() < 1
                || request.maxAppointments() > 60) {
            throw new IllegalArgumentException(
                    "Maximum appointments must be between 1 and 60."
            );
        }

        if (request.minutesPerPatient() < 5
                || request.minutesPerPatient() > 30) {
            throw new IllegalArgumentException(
                    "Minutes per patient must be between 5 and 30."
            );
        }

        long availableMinutes = Duration.between(
                request.startTime(),
                request.endTime()
        ).toMinutes();

        if ((long) request.maxAppointments()
                * request.minutesPerPatient() > availableMinutes) {
            throw new IllegalArgumentException(
                    "The session duration is too short for the requested number of appointments."
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

    private DoctorSessionResponse toResponse(DoctorSession session) {

        Doctor doctor = session.getDoctor();
        Room room = session.getRoom();

        return new DoctorSessionResponse(
                session.getId(),
                doctor.getId(),
                doctor.getFirstName() + " " + doctor.getLastName(),
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
                        session.getMaxAppointments()
                                - session.getLastAppointmentNumber()
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
