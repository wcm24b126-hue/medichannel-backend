package com.medichannel.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.TimeConfiguration;
import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.dto.appointment.AppointmentResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.PaymentStatus;
import com.medichannel.enums.SessionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

@Service
public class AppointmentService {

    private static final List<AppointmentStatus> ACTIVE_STATUSES =
            List.of(
                    AppointmentStatus.BOOKED,
                    AppointmentStatus.ARRIVED,
                    AppointmentStatus.IN_CONSULTATION
            );

    private final AppointmentRepository appointmentRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final PatientDataProtectionService dataProtectionService;
    private final Clock clock;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            DoctorSessionRepository doctorSessionRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            PatientDataProtectionService dataProtectionService,
            Clock clock
    ) {
        this.appointmentRepository = appointmentRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.dataProtectionService = dataProtectionService;
        this.clock = clock;
    }

    // POST: Book an appointment.
    @Transactional
    public AppointmentResponse book(
            UUID authenticatedUserId,
            AppointmentBookingRequest request
    ) {
        Patient patient = getEligiblePatient(authenticatedUserId);

        DoctorSession session = doctorSessionRepository
                .findById(request.sessionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Consultation session was not found."
                ));

        validateSessionCanBeBooked(session);

        boolean alreadyBooked =
                appointmentRepository
                        .existsBySession_IdAndPatient_IdAndStatusIn(
                                session.getId(),
                                patient.getId(),
                                ACTIVE_STATUSES
                        );

        if (alreadyBooked) {
            throw new ConflictException(
                    "You already have an active appointment in this session."
            );
        }

        String encryptedReason =
                dataProtectionService.encrypt(request.reason().trim());

        Appointment appointment = new Appointment(
                session,
                patient,
                encryptedReason
        );

        try {
            /*
             * The PostgreSQL BEFORE INSERT trigger allocates the
             * appointment number. Never set it in Java.
             */
            Appointment saved = appointmentRepository.saveAndFlush(
                    appointment
            );

            if (saved.getAppointmentNumber() == null) {
                throw new IllegalStateException(
                        "Database appointment number was not returned."
                );
            }

            return toResponse(saved);

        } catch (DataIntegrityViolationException exception) {
            /*
             * Database constraints remain the final protection
             * against concurrent duplicate bookings and overbooking.
             */
            throw new ConflictException(
                    "This session is no longer available for this booking. "
                    + "Refresh and try again."
            );
        }
    }

    // GET: List the authenticated customer's appointments.
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getMyAppointments(UUID userId) {

        getEligiblePatient(userId);

        return appointmentRepository
                .findAllByPatient_User_IdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // GET: Retrieve one appointment belonging to the authenticated customer.
    @Transactional(readOnly = true)
    public AppointmentResponse getMyAppointment(
            UUID userId,
            UUID appointmentId
    ) {
        getEligiblePatient(userId);

        Appointment appointment = appointmentRepository
                .findByIdAndPatient_User_Id(appointmentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        return toResponse(appointment);
    }

    // PATCH: Cancel an eligible unpaid appointment.
    @Transactional
    public AppointmentResponse cancelMyAppointment(
            UUID userId,
            UUID appointmentId
    ) {
        getEligiblePatient(userId);

        Appointment appointment = appointmentRepository
                .findByIdAndPatient_User_Id(appointmentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException(
                    "Only booked appointments can be cancelled."
            );
        }

        if (appointment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new ConflictException(
                    "Paid appointments cannot be cancelled through this operation."
            );
        }

        DoctorSession session = appointment.getSession();

        LocalDateTime sessionStart = LocalDateTime.of(
                session.getSessionDate(),
                session.getStartTime()
        );

        LocalDateTime cancellationDeadline = sessionStart.minusHours(3);

        LocalDateTime now = LocalDateTime.now(
                clock.withZone(TimeConfiguration.APPLICATION_ZONE)
        );

        // BR-07: Cancellation is allowed until the 3-hour deadline.
        if (now.isAfter(cancellationDeadline)) {
            throw new ConflictException(
                    "Appointments can only be cancelled at least "
                    + "3 hours before the session."
            );
        }

        appointment.cancel(clock.instant());

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        return toResponse(saved);
    }

    private Patient getEligiblePatient(UUID userId) {

        User user = userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User account was not found."
                ));

        if (!"CUSTOMER".equals(user.getRole().getName())) {
            throw new ForbiddenOperationException(
                    "Only customer accounts can access patient appointments."
            );
        }

        if (!"ACTIVE".equals(user.getAccountStatus())
                || !user.isEmailVerified()) {
            throw new ForbiddenOperationException(
                    "Your account must be active and email-verified."
            );
        }

        return patientRepository.findByUserId(userId)
                .orElseThrow(() -> new ConflictException(
                        "Create your patient profile before using appointments."
                ));
    }

    private void validateSessionCanBeBooked(DoctorSession session) {

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new ConflictException(
                    "This consultation session is not accepting bookings."
            );
        }

        int bookingCloseMinutes =
                session.getConsultationType() == ConsultationType.PHYSICAL
                        ? 60
                        : 30;

        LocalDateTime sessionStart = LocalDateTime.of(
                session.getSessionDate(),
                session.getStartTime()
        );

        LocalDateTime bookingDeadline =
                sessionStart.minusMinutes(bookingCloseMinutes);

        LocalDateTime now = LocalDateTime.now(
                clock.withZone(TimeConfiguration.APPLICATION_ZONE)
        );

        if (!now.isBefore(bookingDeadline)) {
            throw new ConflictException(
                    "The booking deadline for this session has passed."
            );
        }
    }

    private AppointmentResponse toResponse(Appointment appointment) {

        DoctorSession session = appointment.getSession();

        LocalDateTime estimatedConsultationTime =
                LocalDateTime.of(
                        session.getSessionDate(),
                        session.getStartTime()
                ).plusMinutes(
                        (long) (appointment.getAppointmentNumber() - 1)
                                * session.getMinutesPerPatient()
                                + session.getDelayMinutes()
                );

        return new AppointmentResponse(
                appointment.getId(),
                appointment.getBookingReference(),
                session.getId(),
                appointment.getAppointmentNumber(),
                session.getSessionDate(),
                session.getStartTime(),
                estimatedConsultationTime,
                appointment.getStatus(),
                appointment.getPaymentStatus(),
                session.getFeeLkr(),
                appointment.getCreatedAt()
        );
    }
}
