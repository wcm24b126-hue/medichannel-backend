package com.medichannel.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.session.DoctorSessionRequest;
import com.medichannel.dto.session.DoctorSessionResponse;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Room;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.RoomRepository;

@Service
public class DoctorSessionService {

    private final DoctorSessionRepository doctorSessionRepository;
    private final DoctorRepository doctorRepository;
    private final RoomRepository roomRepository;
    private final Clock clock;

    public DoctorSessionService(
            DoctorSessionRepository doctorSessionRepository,
            DoctorRepository doctorRepository,
            RoomRepository roomRepository,
            Clock clock
    ) {
        this.doctorSessionRepository = doctorSessionRepository;
        this.doctorRepository = doctorRepository;
        this.roomRepository = roomRepository;
        this.clock = clock;
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

        try {
            DoctorSession savedSession =
                    doctorSessionRepository.saveAndFlush(session);

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

    // Public discovery of future sessions that still have capacity.
    @Transactional(readOnly = true)
    public List<DoctorSessionResponse> getPublicSessions(
            UUID doctorId,
            UUID specialisationId,
            LocalDate sessionDate
    ) {
        LocalDateTime now = LocalDateTime.now(clock);

        return doctorSessionRepository.findPublicOpenSessions(
                SessionStatus.OPEN,
                now.toLocalDate(),
                now.toLocalTime(),
                doctorId,
                specialisationId,
                sessionDate
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
