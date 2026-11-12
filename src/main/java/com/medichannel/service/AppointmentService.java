package com.medichannel.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.TimeConfiguration;
import com.medichannel.dto.admin.AdminAppointmentResponse;
import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.dto.appointment.AppointmentResponse;
import com.medichannel.dto.doctor.DoctorAppointmentResponse;
import com.medichannel.dto.queue.CompleteAndAdvanceResponse;
import com.medichannel.dto.queue.SessionQueueResponse;
import com.medichannel.dto.queue.SessionQueueSummary;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.NotificationType;
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
    private final NotificationService notificationService;
    private final AppointmentAccessPolicy accessPolicy;
    private final SessionQueueService sessionQueueService;
    private final Clock clock;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            DoctorSessionRepository doctorSessionRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            PatientDataProtectionService dataProtectionService,
            NotificationService notificationService,
            AppointmentAccessPolicy accessPolicy,
            SessionQueueService sessionQueueService,
            Clock clock
    ) {
        this.appointmentRepository = appointmentRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.dataProtectionService = dataProtectionService;
        this.notificationService = notificationService;
        this.accessPolicy = accessPolicy;
        this.sessionQueueService = sessionQueueService;
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

            notifyAppointmentBooked(saved);

            publishQueue(saved);

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

        notifyAppointmentCancelled(saved);

        publishQueue(saved);

        return toResponse(saved);
    }

    // PATCH: Patient check-in for their own booked appointment.
    @Transactional
    public AppointmentResponse checkIn(
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
                    "Only booked appointments can be checked in."
            );
        }

        appointment.markArrived(clock.instant());
        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.create(
                saved.getSession().getDoctor().getUser().getId(),
                NotificationType.APPOINTMENT_ARRIVED,
                "Patient checked in",
                "The patient for appointment #"
                        + saved.getAppointmentNumber()
                        + " has checked in.",
                saved.getId()
        );

        publishQueue(saved);

        return toResponse(saved);
    }

    // =========================================================
    // Doctor consultation portal
    // =========================================================

    @Transactional(readOnly = true)
    public List<DoctorAppointmentResponse> getDoctorAppointments(
            UUID doctorUserId,
            LocalDate date,
            AppointmentStatus status
    ) {
        List<Appointment> appointments;

        if (date != null && status != null) {
            appointments = appointmentRepository
                    .findAllBySession_Doctor_User_IdAndSession_SessionDateAndStatusOrderBySession_StartTimeAsc(
                            doctorUserId,
                            date,
                            status
                    );
        } else if (date != null) {
            appointments = appointmentRepository
                    .findAllBySession_Doctor_User_IdAndSession_SessionDateOrderBySession_StartTimeAsc(
                            doctorUserId,
                            date
                    );
        } else if (status != null) {
            appointments = appointmentRepository
                    .findAllBySession_Doctor_User_IdAndStatusOrderBySession_SessionDateDescSession_StartTimeAsc(
                            doctorUserId,
                            status
                    );
        } else {
            appointments = appointmentRepository
                    .findAllBySession_Doctor_User_IdOrderBySession_SessionDateDescSession_StartTimeAsc(
                            doctorUserId
                    );
        }

        return appointments.stream()
                .map(this::toDoctorResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DoctorAppointmentResponse getDoctorAppointment(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        return toDoctorResponse(
                findDoctorAppointment(doctorUserId, appointmentId)
        );
    }

    @Transactional
    public DoctorAppointmentResponse markArrived(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException(
                    "Only booked appointments can be marked as arrived."
            );
        }

        appointment.markArrived(clock.instant());

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.create(
                saved.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_ARRIVED,
                "You are checked in",
                "Your arrival for appointment #"
                        + saved.getAppointmentNumber()
                        + " has been recorded.",
                saved.getId()
        );

        publishQueue(saved);

        return toDoctorResponse(saved);
    }

    @Transactional
    public DoctorAppointmentResponse startConsultation(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        ensureSessionAcceptsConsultation(appointment);

        if (appointment.getStatus() != AppointmentStatus.BOOKED
                && appointment.getStatus() != AppointmentStatus.ARRIVED) {
            throw new ConflictException(
                    "The consultation cannot be started from its current status."
            );
        }

        if (appointment.getPaymentStatus() != PaymentStatus.PAID) {
            throw new ConflictException(
                    "The appointment must be paid before the consultation can start."
            );
        }

        appointment.startConsultation(clock.instant());

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.create(
                saved.getPatient().getUser().getId(),
                NotificationType.CONSULTATION_STARTED,
                "Consultation started",
                "Your consultation has started. You can now use the consultation chat.",
                saved.getId()
        );

        publishQueue(saved);

        return toDoctorResponse(saved);
    }

    @Transactional
    public DoctorAppointmentResponse completeConsultation(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        if (appointment.getStatus() != AppointmentStatus.IN_CONSULTATION) {
            throw new ConflictException(
                    "Only a consultation in progress can be completed."
            );
        }

        appointment.complete(clock.instant());

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.create(
                saved.getPatient().getUser().getId(),
                NotificationType.CONSULTATION_COMPLETED,
                "Consultation completed",
                "Your consultation is complete. The doctor can now publish a summary.",
                saved.getId()
        );

        publishQueue(saved);

        return toDoctorResponse(saved);
    }

    @Transactional
    public DoctorAppointmentResponse markNoShow(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        if (appointment.getStatus() != AppointmentStatus.BOOKED
                && appointment.getStatus() != AppointmentStatus.ARRIVED) {
            throw new ConflictException(
                    "This appointment cannot be marked as a no-show."
            );
        }

        appointment.markNoShow();

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.create(
                saved.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_CANCELLED,
                "Appointment marked as no-show",
                "You did not attend appointment #"
                        + saved.getAppointmentNumber()
                        + ".",
                saved.getId()
        );

        publishQueue(saved);

        return toDoctorResponse(saved);
    }

    /**
     * The doctor's live queue for one session. Ordered strictly by the
     * appointment number allocated by the database.
     */
    @Transactional(readOnly = true)
    public SessionQueueResponse getDoctorSessionQueue(
            UUID doctorUserId,
            UUID sessionId
    ) {
        DoctorSession session = doctorSessionRepository
                .findByIdAndDoctor_User_Id(sessionId, doctorUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor session was not found."
                ));

        return buildQueueResponse(session);
    }

    /**
     * Complete the appointment in progress and immediately start the
     * next checked-in patient who has paid. Numbers are never reused;
     * only the queue pointer moves.
     */
    @Transactional
    public CompleteAndAdvanceResponse completeAndAdvance(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        if (appointment.getStatus() != AppointmentStatus.IN_CONSULTATION) {
            throw new ConflictException(
                    "Only a consultation in progress can be completed."
            );
        }

        DoctorSession session = appointment.getSession();

        appointment.complete(clock.instant());

        Appointment completed = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.create(
                completed.getPatient().getUser().getId(),
                NotificationType.CONSULTATION_COMPLETED,
                "Consultation completed",
                "Your consultation is complete. The doctor can now publish a summary.",
                completed.getId()
        );

        DoctorAppointmentResponse nextResponse = null;
        boolean paymentRequired = false;

        boolean sessionActive =
                session.getStatus() == SessionStatus.OPEN
                        || session.getStatus() == SessionStatus.IN_PROGRESS;

        if (sessionActive) {
            Optional<Appointment> waiting = appointmentRepository
                    .findFirstBySession_IdAndStatusOrderByAppointmentNumberAsc(
                            session.getId(),
                            AppointmentStatus.ARRIVED
                    );

            if (waiting.isPresent()) {
                Appointment candidate = waiting.get();

                if (candidate.getPaymentStatus() != PaymentStatus.PAID) {
                    paymentRequired = true;
                } else {
                    candidate.startConsultation(clock.instant());

                    Appointment started = appointmentRepository
                            .saveAndFlush(candidate);

                    notificationService.create(
                            started.getPatient().getUser().getId(),
                            NotificationType.CONSULTATION_STARTED,
                            "You are next",
                            "Your consultation has started.",
                            started.getId()
                    );

                    nextResponse = toDoctorResponse(started);
                }
            }
        }

        publishQueue(completed);

        return new CompleteAndAdvanceResponse(
                toDoctorResponse(completed),
                nextResponse,
                paymentRequired,
                buildQueueResponse(session)
        );
    }

    private SessionQueueResponse buildQueueResponse(DoctorSession session) {
        List<DoctorAppointmentResponse> appointments = appointmentRepository
                .findBySession_IdOrderByAppointmentNumberAsc(session.getId())
                .stream()
                .map(this::toDoctorResponse)
                .toList();

        SessionQueueSummary summary =
                sessionQueueService.buildSummary(session);

        return new SessionQueueResponse(
                session.getId(),
                summary.nowServing(),
                summary.nextNumber(),
                summary.remainingAppointments(),
                summary.maxAppointments(),
                summary.status(),
                appointments
        );
    }

    private void publishQueue(Appointment appointment) {
        SessionQueueSummary summary = sessionQueueService.buildSummary(
                appointment.getSession()
        );

        sessionQueueService.broadcast(summary);
        sessionQueueService.broadcastAppointmentStatus(
                appointment.getId(),
                appointment.getStatus(),
                clock.instant()
        );
    }

    // =========================================================
    // Administrative oversight
    // =========================================================
    @Transactional(readOnly = true)
    public List<AdminAppointmentResponse> getForOversight(
            UUID sessionId,
            UUID doctorId,
            AppointmentStatus status,
            LocalDate sessionDate
    ) {
        return appointmentRepository.findForOversight(
                sessionId,
                doctorId,
                status,
                sessionDate
        ).stream().map(this::toAdminResponse).toList();
    }

    @Transactional(readOnly = true)
    public AdminAppointmentResponse getForOversight(UUID appointmentId) {
        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        return toAdminResponse(appointment);
    }

    private Appointment findDoctorAppointment(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        Appointment appointment = appointmentRepository
                .findByIdAndSession_Doctor_User_Id(
                        appointmentId,
                        doctorUserId
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        if (!accessPolicy.isAssignedDoctor(appointment, doctorUserId)) {
            throw new ForbiddenOperationException(
                    "Only the assigned doctor can manage this consultation."
            );
        }

        return appointment;
    }

    private void ensureSessionAcceptsConsultation(Appointment appointment) {
        SessionStatus status = appointment.getSession().getStatus();

        if (status == SessionStatus.CANCELLED
                || status == SessionStatus.COMPLETED
                || status == SessionStatus.SCHEDULED) {
            throw new ConflictException(
                    "The consultation session is not currently active."
            );
        }
    }

    private void notifyAppointmentBooked(Appointment appointment) {
        String when = appointment.getSession().getSessionDate()
                + " at " + appointment.getSession().getStartTime();

        notificationService.create(
                appointment.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_BOOKED,
                "Appointment booked",
                "Appointment #"
                        + appointment.getAppointmentNumber()
                        + " is booked for " + when + ".",
                appointment.getId()
        );

        notificationService.create(
                appointment.getSession().getDoctor().getUser().getId(),
                NotificationType.APPOINTMENT_BOOKED,
                "New appointment booked",
                "A patient booked appointment #"
                        + appointment.getAppointmentNumber()
                        + " for " + when + ".",
                appointment.getId()
        );
    }

    private void notifyAppointmentCancelled(Appointment appointment) {
        String when = appointment.getSession().getSessionDate()
                + " at " + appointment.getSession().getStartTime();

        notificationService.create(
                appointment.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_CANCELLED,
                "Appointment cancelled",
                "Appointment #"
                        + appointment.getAppointmentNumber()
                        + " on " + when + " was cancelled.",
                appointment.getId()
        );

        notificationService.create(
                appointment.getSession().getDoctor().getUser().getId(),
                NotificationType.APPOINTMENT_CANCELLED,
                "Appointment cancelled",
                "The patient cancelled appointment #"
                        + appointment.getAppointmentNumber()
                        + " on " + when + ".",
                appointment.getId()
        );
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

        return new AppointmentResponse(
                appointment.getId(),
                appointment.getBookingReference(),
                session.getId(),
                appointment.getAppointmentNumber(),
                session.getSessionDate(),
                session.getStartTime(),
                estimatedConsultationTime(appointment),
                appointment.getStatus(),
                appointment.getPaymentStatus(),
                session.getFeeLkr(),
                appointment.getCreatedAt(),
                session.getDoctor().getId(),
                session.getDoctor().getFirstName()
                        + " " + session.getDoctor().getLastName(),
                session.getDoctor().getSpecialisation().getName(),
                session.getConsultationType(),
                session.getRoom() == null
                        ? null
                        : session.getRoom().getRoomNumber(),
                appointment.getPaymentMethod(),
                appointment.getArrivedAt(),
                appointment.getConsultationStartedAt(),
                appointment.getConsultationCompletedAt(),
                appointment.getCancelledAt()
        );
    }

    private LocalDateTime estimatedConsultationTime(
            Appointment appointment
    ) {
        DoctorSession session = appointment.getSession();

        return LocalDateTime.of(
                session.getSessionDate(),
                session.getStartTime()
        ).plusMinutes(
                (long) (appointment.getAppointmentNumber() - 1)
                        * session.getMinutesPerPatient()
                        + session.getDelayMinutes()
        );
    }

    private DoctorAppointmentResponse toDoctorResponse(
            Appointment appointment
    ) {
        DoctorSession session = appointment.getSession();
        Patient patient = appointment.getPatient();

        String patientName = dataProtectionService.decrypt(
                patient.getFirstNameEncrypted()
        ) + " " + dataProtectionService.decrypt(
                patient.getLastNameEncrypted()
        );

        return new DoctorAppointmentResponse(
                appointment.getId(),
                appointment.getBookingReference(),
                appointment.getAppointmentNumber(),
                session.getId(),
                patient.getId(),
                patientName,
                session.getSessionDate(),
                session.getStartTime(),
                estimatedConsultationTime(appointment),
                session.getConsultationType(),
                session.getRoom() == null
                        ? null
                        : session.getRoom().getRoomNumber(),
                appointment.getStatus(),
                appointment.getPaymentStatus(),
                appointment.getPaymentMethod(),
                session.getFeeLkr(),
                appointment.getCreatedAt(),
                appointment.getArrivedAt(),
                appointment.getConsultationStartedAt(),
                appointment.getConsultationCompletedAt(),
                appointment.getCancelledAt()
        );
    }

    private AdminAppointmentResponse toAdminResponse(
            Appointment appointment
    ) {
        DoctorSession session = appointment.getSession();
        Patient patient = appointment.getPatient();

        String patientName = dataProtectionService.decrypt(
                patient.getFirstNameEncrypted()
        ) + " " + dataProtectionService.decrypt(
                patient.getLastNameEncrypted()
        );

        return new AdminAppointmentResponse(
                appointment.getId(),
                appointment.getBookingReference(),
                appointment.getAppointmentNumber(),
                session.getId(),
                session.getDoctor().getId(),
                session.getDoctor().getFirstName()
                        + " " + session.getDoctor().getLastName(),
                patient.getId(),
                patientName,
                session.getSessionDate(),
                session.getStartTime(),
                session.getConsultationType(),
                appointment.getStatus(),
                appointment.getPaymentStatus(),
                appointment.getPaymentMethod(),
                session.getFeeLkr(),
                appointment.getCreatedAt(),
                appointment.getArrivedAt(),
                appointment.getConsultationStartedAt(),
                appointment.getConsultationCompletedAt(),
                appointment.getCancelledAt()
        );
    }
}
