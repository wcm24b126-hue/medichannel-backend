package com.medichannel.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.config.TimeConfiguration;
import com.medichannel.dto.admin.AdminAppointmentResponse;
import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.dto.appointment.AppointmentResponse;
import com.medichannel.dto.appointment.WalkInBookingRequest;
import com.medichannel.dto.doctor.DoctorAppointmentResponse;
import com.medichannel.dto.queue.CompleteAndAdvanceResponse;
import com.medichannel.dto.queue.SessionQueueResponse;
import com.medichannel.dto.queue.SessionQueueSummary;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationPeriod;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.NotificationType;
import com.medichannel.enums.PaymentStatus;
import com.medichannel.enums.SessionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.NotificationRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

@Service
public class AppointmentService {

    private static final List<AppointmentStatus> ACTIVE_STATUSES =
            List.of(
                    AppointmentStatus.BOOKED,
                    AppointmentStatus.CHECKED_IN,
                    AppointmentStatus.ARRIVED,
                    AppointmentStatus.IN_CONSULTATION
            );

    private final AppointmentRepository appointmentRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final PatientDataProtectionService dataProtectionService;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final AppointmentAccessPolicy accessPolicy;
    private final SessionQueueService sessionQueueService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    private static final String THREE_NUMBERS_AWAY_TITLE =
            "You are 3 numbers away";

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            DoctorSessionRepository doctorSessionRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            NotificationRepository notificationRepository,
            PatientDataProtectionService dataProtectionService,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            NotificationService notificationService,
            AppointmentAccessPolicy accessPolicy,
            SessionQueueService sessionQueueService,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.appointmentRepository = appointmentRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.dataProtectionService = dataProtectionService;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
        this.accessPolicy = accessPolicy;
        this.sessionQueueService = sessionQueueService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    // POST: Book an appointment.
    @Transactional
    public AppointmentResponse book(
            UUID authenticatedUserId,
            AppointmentBookingRequest request
    ) {
        Patient patient = getEligiblePatient(authenticatedUserId);

        DoctorSession session = findBookableSession(request.sessionId(), true);
        validateRequestedPeriod(session, request.period());
        return createAppointment(session, patient, request.reason());
    }

    /**
     * POST: reception books an appointment for a patient who is physically
     * present. Staff may not use the patient self-booking endpoint because
     * it derives the patient from the authenticated account.
     */
    @Transactional
    public AppointmentResponse bookWalkIn(
            UUID staffUserId,
            WalkInBookingRequest request
    ) {
        User staff = userRepository.findByIdWithRole(staffUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User account was not found."
                ));

        String staffRole = staff.getRole().getName();
        if (!"ADMIN".equals(staffRole) && !"MANAGER".equals(staffRole)) {
            throw new ForbiddenOperationException(
                    "Only reception staff can book for walk-in patients."
            );
        }

        String phone = request.phone().trim().replaceAll("[\\s-]", "");
        if (!phone.matches("07[0-9]{8}")) {
            throw new IllegalArgumentException(
                    "Please provide a valid mobile number in the 07XXXXXXXX format."
            );
        }

        Patient patient = findOrCreateWalkInPatient(
                request.firstName().trim(),
                request.lastName().trim(),
                phone
        );

        // Reception walk-ins are allowed even for a staff-created session
        // outside the published clinic window; patient self-booking is not.
        DoctorSession session = findBookableSession(request.sessionId(), false);
        return createAppointment(session, patient, request.reason());
    }

    private DoctorSession findBookableSession(
            UUID sessionId,
            boolean enforcePublishedSchedule
    ) {
        DoctorSession session = doctorSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Consultation session was not found."
                ));

        validateSessionCanBeBooked(session, enforcePublishedSchedule);
        return session;
    }

    /**
     * The period chosen in the expanded booking panel must match the
     * published window of the selected session. This prevents a client from
     * issuing a number for one period against a session in another period.
     */
    private void validateRequestedPeriod(
            DoctorSession session,
            ConsultationPeriod requestedPeriod
    ) {
        ConsultationPeriod sessionPeriod =
                ClinicSchedule.periodFor(session.getStartTime());

        if (requestedPeriod == null || sessionPeriod == null
                || requestedPeriod != sessionPeriod) {
            throw new IllegalArgumentException(
                    "The selected period does not match this session. "
                            + "Morning is 07:00–11:00; Evening is 13:00–15:00."
            );
        }
    }

    private AppointmentResponse createAppointment(
            DoctorSession session,
            Patient patient,
            String reason
    ) {
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

        Appointment appointment = new Appointment(
                session,
                patient,
                dataProtectionService.encrypt(reason.trim())
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

    private Patient findOrCreateWalkInPatient(
            String firstName,
            String lastName,
            String phone
    ) {
        String email = "walkin." + phone + "@medichannel.local";

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (!"CUSTOMER".equals(user.getRole().getName())) {
                throw new ConflictException(
                        "This phone number is linked to a staff account."
                );
            }
            return patientRepository.findByUserId(user.getId())
                    .orElseGet(() -> createWalkInPatient(
                            user, firstName, lastName, phone
                    ));
        }

        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException(
                        "The required CUSTOMER role is not configured."
                ));

        // Walk-in patients do not sign in at the desk, so the generated
        // credential is never disclosed. The account is active so staff
        // can still record payments and the appointment is fully persisted.
        User user = new User(
                customerRole,
                email,
                passwordEncoder.encode(UUID.randomUUID().toString())
        );
        user.activateAfterVerification();
        User savedUser = userRepository.saveAndFlush(user);

        return createWalkInPatient(savedUser, firstName, lastName, phone);
    }

    private Patient createWalkInPatient(
            User user,
            String firstName,
            String lastName,
            String phone
    ) {
        Patient patient = new Patient(
                user,
                dataProtectionService.encrypt(firstName),
                dataProtectionService.encrypt(lastName),
                dataProtectionService.encrypt(phone),
                null,
                null,
                null
        );

        return patientRepository.saveAndFlush(patient);
    }


    // GET: List the authenticated customer's appointments.
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getMyAppointments(UUID userId) {

        getEligiblePatient(userId);

        return appointmentRepository
                .findAllByPatient_User_IdAndPatientHiddenAtIsNullOrderByCreatedAtDesc(userId)
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

    /**
     * Clear a closed appointment from the patient's own history view. The
     * appointment and every related record are retained; only this
     * participant's visibility flag is set.
     */
    @Transactional
    public void hideMyAppointment(
            UUID userId,
            UUID appointmentId,
            String ipAddress
    ) {
        getEligiblePatient(userId);

        Appointment appointment = appointmentRepository
                .findByIdAndPatient_User_Id(appointmentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        ensureHistoryCanBeHidden(appointment);
        appointment.hideFromPatient(clock.instant());
        appointmentRepository.saveAndFlush(appointment);

        auditLogService.log(
                userId,
                "CUSTOMER",
                "APPOINTMENT_HISTORY_HIDDEN",
                "Patient cleared appointment " + appointmentId
                        + " from their history view.",
                ipAddress,
                appointmentId
        );
    }

    // PATCH: Cancel an unpaid booking belonging to the signed-in patient.
    @Transactional
    public AppointmentResponse cancelMyAppointment(
            UUID userId,
            UUID appointmentId,
            String reason
    ) {
        getEligiblePatient(userId);

        Appointment appointment = appointmentRepository
                .findByIdAndPatient_User_Id(appointmentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new ConflictException(
                    "Appointment is already cancelled."
            );
        }

        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException(
                    "Only booked appointments can be cancelled by patients."
            );
        }

        if (appointment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new ConflictException(
                    "Paid appointments cannot be cancelled by patients. "
                    + "Please contact the hospital staff."
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

        if (now.isAfter(cancellationDeadline)) {
            throw new ConflictException(
                    "Appointments can only be cancelled at least three hours "
                    + "before the session starts."
            );
        }

        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isBlank()) {
            throw new IllegalArgumentException(
                    "A cancellation reason is required."
            );
        }
        if (trimmedReason.length() > 500) {
            trimmedReason = trimmedReason.substring(0, 500);
        }

        appointment.cancelForPatient(
                clock.instant(),
                dataProtectionService.encrypt(trimmedReason)
        );

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notifyAppointmentCancelled(saved);

        publishQueue(saved);

        return toResponse(saved);
    }

    @Transactional
    public AppointmentResponse cancelAppointment(
            UUID userId,
            String role,
            UUID appointmentId,
            String reason
    ) {
        if ("DOCTOR".equalsIgnoreCase(role)) {
            throw new ForbiddenOperationException(
                    "Doctors cannot directly cancel appointments. Please request cancellation through a hospital manager or administrator."
            );
        }

        if ("ADMIN".equalsIgnoreCase(role) || "MANAGER".equalsIgnoreCase(role)) {
            Appointment appointment = appointmentRepository
                    .findById(appointmentId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Appointment was not found."
                    ));

            if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
                throw new ConflictException("Appointment is already cancelled.");
            }
            if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
                throw new ConflictException("Completed appointments cannot be cancelled.");
            }

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
            appointment.cancelByStaff(clock.instant(), encryptedReason);
            Appointment saved = appointmentRepository.saveAndFlush(appointment);

            notifyAppointmentCancelled(saved);
            publishQueue(saved);

            return toResponse(saved);
        }

        return cancelMyAppointment(userId, appointmentId, reason);
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
        if (!"ACCEPTED".equals(appointment.getAcceptanceStatus())) {
            throw new ConflictException(
                    "The doctor must accept the appointment before check-in."
            );
        }
        if (appointment.getPaymentStatus() != PaymentStatus.PAID) {
            throw new ConflictException(
                    "The appointment must be paid before check-in."
            );
        }

        appointment.markCheckedIn(clock.instant());
        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.createOnceForAppointment(
                saved.getSession().getDoctor().getUser().getId(),
                NotificationType.APPOINTMENT_ARRIVED,
                "Patient checked in",
                "Your patient for appointment #"
                        + saved.getAppointmentNumber()
                        + " has checked in and is waiting for arrival confirmation.",
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
                    .findAllBySession_Doctor_User_IdAndSession_SessionDateAndStatusAndDoctorHiddenAtIsNullOrderBySession_StartTimeAsc(
                            doctorUserId,
                            date,
                            status
                    );
        } else if (date != null) {
            appointments = appointmentRepository
                    .findAllBySession_Doctor_User_IdAndSession_SessionDateAndDoctorHiddenAtIsNullOrderBySession_StartTimeAsc(
                            doctorUserId,
                            date
                    );
        } else if (status != null) {
            appointments = appointmentRepository
                    .findAllBySession_Doctor_User_IdAndStatusAndDoctorHiddenAtIsNullOrderBySession_SessionDateDescSession_StartTimeAsc(
                            doctorUserId,
                            status
                    );
        } else {
            appointments = appointmentRepository
                    .findAllBySession_Doctor_User_IdAndDoctorHiddenAtIsNullOrderBySession_SessionDateDescSession_StartTimeAsc(
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

    /**
     * Clear a closed appointment from the assigned doctor's own queue/history
     * view. The patient's view and the underlying records are unaffected.
     */
    @Transactional
    public void hideDoctorAppointment(
            UUID doctorUserId,
            UUID appointmentId,
            String ipAddress
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        ensureHistoryCanBeHidden(appointment);
        appointment.hideFromDoctor(clock.instant());
        appointmentRepository.saveAndFlush(appointment);

        auditLogService.log(
                doctorUserId,
                "DOCTOR",
                "APPOINTMENT_HISTORY_HIDDEN",
                "Doctor cleared appointment " + appointmentId
                        + " from their session history view.",
                ipAddress,
                appointmentId
        );
    }

    @Transactional
    public DoctorAppointmentResponse acceptAppointmentByDoctor(
            UUID doctorUserId,
            UUID appointmentId
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException(
                    "Only booked appointments can be accepted."
            );
        }
        if (!"PENDING".equals(appointment.getAcceptanceStatus())) {
            throw new ConflictException(
                    "This appointment has already been accepted or declined."
            );
        }
        if (appointment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new ConflictException(
                    "An appointment that is already paid cannot be accepted."
            );
        }

        appointment.acceptByDoctor(clock.instant());
        Appointment saved = appointmentRepository.saveAndFlush(appointment);

        String when = appointment.getSession().getSessionDate()
                + " at " + appointment.getSession().getStartTime();

        notificationService.createOnceForAppointment(
                appointment.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_BOOKED,
                "Appointment Accepted",
                "Your appointment has been accepted by the doctor for " + when + ". Payment is now available.",
                appointment.getId()
        );

        publishQueue(saved);

        return toDoctorResponse(saved);
    }

    @Transactional
    public DoctorAppointmentResponse declineAppointmentByDoctor(
            UUID doctorUserId,
            UUID appointmentId,
            String reason
    ) {
        Appointment appointment = findDoctorAppointment(
                doctorUserId,
                appointmentId
        );

        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException(
                    "Only booked appointments can be declined."
            );
        }
        if (!"PENDING".equals(appointment.getAcceptanceStatus())) {
            throw new ConflictException(
                    "This appointment has already been accepted or declined."
            );
        }
        if (appointment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new ConflictException(
                    "An appointment that is already paid cannot be declined."
            );
        }

        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isBlank()) {
            throw new IllegalArgumentException(
                    "A cancellation reason is required."
            );
        }
        if (trimmedReason.length() > 500) {
            trimmedReason = trimmedReason.substring(0, 500);
        }

        appointment.declineByDoctor(
                clock.instant(),
                dataProtectionService.encrypt(trimmedReason)
        );
        Appointment saved = appointmentRepository.saveAndFlush(appointment);

        String when = appointment.getSession().getSessionDate()
                + " at " + appointment.getSession().getStartTime();

        notificationService.createOnceForAppointment(
                appointment.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_CANCELLED,
                "Appointment Declined",
                "Your appointment could not be accepted for " + when + ". Please book another session.",
                appointment.getId()
        );

        publishQueue(saved);

        return toDoctorResponse(saved);
    }

    /**
     * Delays one appointment and every active appointment behind it in the
     * same session. Earlier patients are unaffected; a later cancellation
     * cannot remove the delay that has already been applied to the people
     * still waiting.
     */
    @Transactional
    public AppointmentResponse delayAppointmentQueue(
            UUID userId,
            String role,
            UUID appointmentId,
            int minutes
    ) {
        if (minutes < 0) {
            throw new IllegalArgumentException("Delay cannot be negative.");
        }

        Appointment target;
        if ("DOCTOR".equalsIgnoreCase(role)) {
            target = findDoctorAppointment(userId, appointmentId);
        } else if ("ADMIN".equalsIgnoreCase(role)
                || "MANAGER".equalsIgnoreCase(role)) {
            target = appointmentRepository.findById(appointmentId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Appointment was not found."
                    ));
        } else {
            throw new ForbiddenOperationException(
                    "Only hospital staff or the assigned doctor can delay a queue."
            );
        }

        if (target.getAppointmentNumber() == null
                || !ACTIVE_STATUSES.contains(target.getStatus())) {
            throw new ConflictException(
                    "Only an active queued appointment can be delayed."
            );
        }

        List<Appointment> affected = appointmentRepository
                .findBySession_IdAndAppointmentNumberGreaterThanEqualAndStatusInOrderByAppointmentNumberAsc(
                        target.getSession().getId(),
                        target.getAppointmentNumber(),
                        ACTIVE_STATUSES
                );

        for (Appointment appointment : affected) {
            appointment.addDelay(minutes);
            appointmentRepository.save(appointment);

            notificationService.create(
                    appointment.getPatient().getUser().getId(),
                    NotificationType.APPOINTMENT_REMINDER,
                    "Queue delayed",
                    "Appointment #" + appointment.getAppointmentNumber()
                            + " has been delayed by " + minutes
                            + " minute(s). Your new estimated time is "
                            + estimatedConsultationTime(appointment) + ".",
                    appointment.getId()
            );
        }

        appointmentRepository.flush();
        publishQueue(target);
        auditLogService.log(
                userId,
                role,
                "QUEUE_DELAY_APPLIED",
                "Appointment #" + target.getAppointmentNumber()
                        + " delayed by " + minutes
                        + " minute(s); " + affected.size()
                        + " queued appointment(s) affected.",
                "10.0.4.18",
                target.getId()
        );

        return toResponse(target);
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

        if (appointment.getStatus() != AppointmentStatus.CHECKED_IN) {
            throw new ConflictException(
                    "Only checked-in appointments can be marked as arrived."
            );
        }
        if (!"ACCEPTED".equals(appointment.getAcceptanceStatus())) {
            throw new ConflictException(
                    "The doctor must accept the appointment before arrival."
            );
        }
        if (appointment.getPaymentStatus() != PaymentStatus.PAID) {
            throw new ConflictException(
                    "The appointment must be paid before arrival."
            );
        }

        appointment.markArrived(clock.instant());

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.createOnceForAppointment(
                saved.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_ARRIVED,
                "Arrival recorded",
                "Your arrival for appointment #"
                        + saved.getAppointmentNumber()
                        + " has been recorded.",
                saved.getId()
        );

        publishQueue(saved);

        return toDoctorResponse(saved);
    }

    /**
     * Reception staff (manager or administrator) marks a booked
     * appointment as arrived. Unlike the doctor variant this does not
     * require the caller to own the session.
     */
    @Transactional
    public AdminAppointmentResponse markArrivedByStaff(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment not found."
                ));

        if (appointment.getStatus() != AppointmentStatus.CHECKED_IN) {
            throw new ConflictException(
                    "Only checked-in appointments can be marked as arrived."
            );
        }
        if (!"ACCEPTED".equals(appointment.getAcceptanceStatus())) {
            throw new ConflictException(
                    "The doctor must accept the appointment before arrival."
            );
        }
        if (appointment.getPaymentStatus() != PaymentStatus.PAID) {
            throw new ConflictException(
                    "The appointment must be paid before arrival."
            );
        }

        appointment.markArrived(clock.instant());

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.createOnceForAppointment(
                saved.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_ARRIVED,
                "Arrival recorded",
                "Your arrival for appointment #"
                        + saved.getAppointmentNumber()
                        + " has been recorded.",
                saved.getId()
        );

        notificationService.createOnceForAppointment(
                saved.getSession().getDoctor().getUser().getId(),
                NotificationType.APPOINTMENT_ARRIVED,
                "Patient arrived",
                "Your patient for appointment #"
                        + saved.getAppointmentNumber()
                        + " has arrived.",
                saved.getId()
        );

        publishQueue(saved);

        return toAdminResponse(saved);
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

        if (!"ACCEPTED".equals(appointment.getAcceptanceStatus())) {
            throw new ConflictException(
                    "The doctor must accept the appointment before the consultation starts."
            );
        }

        boolean physical = appointment.getSession().getConsultationType()
                == ConsultationType.PHYSICAL;

        if (physical
                && appointment.getStatus() != AppointmentStatus.ARRIVED) {
            throw new ConflictException(
                    "Physical consultations require doctor-confirmed arrival before they can start."
            );
        }

        if (!physical
                && appointment.getStatus() != AppointmentStatus.BOOKED
                && appointment.getStatus() != AppointmentStatus.CHECKED_IN
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

        String startedMessage = physical
                ? "Your consultation has started. Please proceed to the consultation room."
                : "Your online consultation is ready. You can join now.";

        notificationService.createOnceForAppointment(
                saved.getPatient().getUser().getId(),
                NotificationType.CONSULTATION_STARTED,
                "Consultation started",
                startedMessage,
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

        notificationService.createOnceForAppointment(
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
                && appointment.getStatus() != AppointmentStatus.CHECKED_IN) {
            throw new ConflictException(
                    "This appointment cannot be marked as a no-show."
            );
        }
        if (!"ACCEPTED".equals(appointment.getAcceptanceStatus())) {
            throw new ConflictException(
                    "The doctor must accept the appointment before marking a no-show."
            );
        }

        appointment.markNoShow();

        Appointment saved = appointmentRepository.saveAndFlush(
                appointment
        );

        notificationService.createOnceForAppointment(
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

        notificationService.createOnceForAppointment(
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

                if (candidate.getPaymentStatus() != PaymentStatus.PAID
                        || !"ACCEPTED".equals(
                                candidate.getAcceptanceStatus()
                        )) {
                    paymentRequired = true;
                } else {
                    candidate.startConsultation(clock.instant());

                    Appointment started = appointmentRepository
                            .saveAndFlush(candidate);

                    // The next patient's room flips from "waiting" to
                    // "in consultation" without a page reload.
                    sessionQueueService.broadcastAppointmentStatus(
                            started.getId(),
                            started.getStatus(),
                            clock.instant()
                    );

                    // RT-04: being called is distinct from the
                    // CONSULTATION_STARTED notification sent by the
                    // explicit start endpoint.
                    notificationService.createOnceForAppointment(
                            started.getPatient().getUser().getId(),
                            NotificationType.CALLED,
                            "You are next",
                            "You have been called. Please proceed to the consultation room.",
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
                .findBySession_IdAndDoctorHiddenAtIsNullOrderByAppointmentNumberAsc(session.getId())
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
                summary.availableSlots(),
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

        notifyThreeNumbersAway(appointment.getSession(), summary.nowServing());
    }

    /**
     * RT-04: notify the patient who is three active places behind the
     * number currently being served. Cancelled and no-show numbers leave
     * gaps, so the third appointment is selected by ordering the active
     * queue (LIMIT 1 OFFSET 2) rather than by arithmetic. The persisted
     * notification is the de-duplication marker.
     */
    private void notifyThreeNumbersAway(
            DoctorSession session,
            Integer nowServing
    ) {
        if (nowServing == null) {
            return;
        }

        appointmentRepository
                .findThirdActiveAppointmentAfterNumber(
                        session.getId(),
                        nowServing
                )
                .filter(appointment -> appointment.getStatus()
                        == AppointmentStatus.BOOKED
                        || appointment.getStatus()
                        == AppointmentStatus.CHECKED_IN
                        || appointment.getStatus()
                        == AppointmentStatus.ARRIVED)
                .filter(appointment -> !notificationRepository
                        .existsByAppointment_IdAndTypeAndTitle(
                                appointment.getId(),
                                NotificationType.APPOINTMENT_REMINDER,
                                THREE_NUMBERS_AWAY_TITLE
                        ))
                .ifPresent(appointment -> notificationService.create(
                        appointment.getPatient().getUser().getId(),
                        NotificationType.APPOINTMENT_REMINDER,
                        THREE_NUMBERS_AWAY_TITLE,
                        "You are 3 numbers away. Appointment #"
                                + appointment.getAppointmentNumber()
                                + " will be called soon; please stay close to the consultation room.",
                        appointment.getId()
                ));
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

    private void ensureHistoryCanBeHidden(Appointment appointment) {
        AppointmentStatus status = appointment.getStatus();
        if (status != AppointmentStatus.COMPLETED
                && status != AppointmentStatus.NO_SHOW
                && status != AppointmentStatus.CANCELLED) {
            throw new ConflictException(
                    "Only completed or closed appointments can be cleared from history."
            );
        }
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
        String consultationTypeLabel = appointment.getSession().getConsultationType()
                == ConsultationType.ONLINE ? "online" : "physical";

        notificationService.createOnceForAppointment(
                appointment.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_BOOKED,
                "Appointment booked",
                "Appointment #"
                        + appointment.getAppointmentNumber()
                        + " is booked for " + when + ".",
                appointment.getId()
        );

        notificationService.createOnceForAppointment(
                appointment.getSession().getDoctor().getUser().getId(),
                NotificationType.APPOINTMENT_BOOKED,
                "New appointment booked",
                "A patient booked " + consultationTypeLabel + " appointment #"
                        + appointment.getAppointmentNumber()
                        + " for " + when + ".",
                appointment.getId()
        );
    }

    private void notifyAppointmentCancelled(Appointment appointment) {
        String when = appointment.getSession().getSessionDate()
                + " at " + appointment.getSession().getStartTime();

        notificationService.createOnceForAppointment(
                appointment.getPatient().getUser().getId(),
                NotificationType.APPOINTMENT_CANCELLED,
                "Appointment cancelled",
                "Appointment #"
                        + appointment.getAppointmentNumber()
                        + " on " + when + " was cancelled.",
                appointment.getId()
        );

        notificationService.createOnceForAppointment(
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

    private void validateSessionCanBeBooked(
            DoctorSession session,
            boolean enforcePublishedSchedule
    ) {

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new ConflictException(
                    "This consultation session is not accepting bookings."
            );
        }

        if (enforcePublishedSchedule && !session.getDoctor().isAvailable()) {
            throw new ConflictException(
                    "This doctor is not currently accepting bookings."
            );
        }

        if (enforcePublishedSchedule && !ClinicSchedule.isBookableSession(session)) {
            throw new ConflictException(
                    "This session is outside the published windows "
                            + "(" + ClinicSchedule.allowedWindowLabel() + ") "
                            + "or does not use a "
                            + ClinicSchedule.MINUTES_PER_PATIENT
                            + "-minute patient slot."
            );
        }

        LocalTime nowTime = LocalTime.now(
                clock.withZone(TimeConfiguration.APPLICATION_ZONE)
        );
        if (ClinicSchedule.isBreak(nowTime)) {
            throw new ConflictException(
                    "Appointment numbers are not issued during the "
                            + "11:00–13:00 break. Please book outside the break."
            );
        }

        int bookingCloseMinutes = ClinicSchedule.bookingCloseMinutes(
                session.getConsultationType()
        );

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

    /**
     * Staff/system reasons carry an internal marker so the database can
     * distinguish them from patient cancellations. Strip that marker
     * before returning the reason to the patient.
     */
    private String decryptCancellationReason(String storedReason) {
        if (storedReason == null) {
            return null;
        }

        String value = storedReason;
        if (value.startsWith(Appointment.STAFF_CANCELLATION_PREFIX)) {
            value = value.substring(
                    Appointment.STAFF_CANCELLATION_PREFIX.length()
            );
        } else if (value.startsWith(Appointment.SYSTEM_CANCELLATION_PREFIX)) {
            value = value.substring(
                    Appointment.SYSTEM_CANCELLATION_PREFIX.length()
            );
        }

        if (!value.startsWith("v1:")) {
            return value;
        }

        return dataProtectionService.decrypt(value);
    }

    private AppointmentResponse toResponse(Appointment appointment) {

        DoctorSession session = appointment.getSession();
        LocalDateTime estimatedStart = estimatedConsultationTime(appointment);
        int slotMinutes = session.getMinutesPerPatient();

        return new AppointmentResponse(
                appointment.getId(),
                appointment.getBookingReference(),
                session.getId(),
                appointment.getAppointmentNumber(),
                session.getSessionDate(),
                session.getStartTime(),
                estimatedStart,
                estimatedStart.plusMinutes(slotMinutes),
                slotMinutes,
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
                appointment.getCheckedInAt(),
                appointment.getArrivedAt(),
                appointment.getConsultationStartedAt(),
                appointment.getConsultationCompletedAt(),
                appointment.getCancelledAt(),
                appointment.getAcceptanceStatus(),
                appointment.getDoctorAcceptedAt(),
                decryptCancellationReason(
                        appointment.getCancellationReasonEncrypted()
                )
        );
    }

    private LocalDateTime estimatedConsultationTime(
            Appointment appointment
    ) {
        DoctorSession session = appointment.getSession();

        int position = appointment.getAppointmentNumber() == null
                ? 1
                : (int) Math.max(1, appointmentRepository.countActiveUpToNumber(
                        session.getId(),
                        appointment.getAppointmentNumber(),
                        ACTIVE_STATUSES
                ));

        return LocalDateTime.of(
                session.getSessionDate(),
                session.getStartTime()
        ).plusMinutes(
                (long) (position - 1)
                        * session.getMinutesPerPatient()
                        + session.getDelayMinutes()
                        + appointment.getDelayMinutes()
        );
    }

    private DoctorAppointmentResponse toDoctorResponse(
            Appointment appointment
    ) {
        DoctorSession session = appointment.getSession();
        Patient patient = appointment.getPatient();

        String patientName;
        try {
            patientName = (dataProtectionService.decrypt(
                    patient.getFirstNameEncrypted()
            ) + " " + dataProtectionService.decrypt(
                    patient.getLastNameEncrypted()
            )).trim();
            if (patientName.isBlank()) {
                patientName = "Patient #" + appointment.getAppointmentNumber();
            }
        } catch (Exception e) {
            patientName = "Patient #" + appointment.getAppointmentNumber();
        }

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
                appointment.getCheckedInAt(),
                appointment.getArrivedAt(),
                appointment.getConsultationStartedAt(),
                appointment.getConsultationCompletedAt(),
                appointment.getCancelledAt(),
                appointment.getAcceptanceStatus(),
                appointment.getDoctorAcceptedAt()
        );
    }

    private AdminAppointmentResponse toAdminResponse(
            Appointment appointment
    ) {
        DoctorSession session = appointment.getSession();
        Patient patient = appointment.getPatient();

        String patientName;
        try {
            patientName = (dataProtectionService.decrypt(
                    patient.getFirstNameEncrypted()
            ) + " " + dataProtectionService.decrypt(
                    patient.getLastNameEncrypted()
            )).trim();
            if (patientName.isBlank()) {
                patientName = "Patient #" + appointment.getAppointmentNumber();
            }
        } catch (Exception e) {
            patientName = "Patient #" + appointment.getAppointmentNumber();
        }

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
                appointment.getCheckedInAt(),
                appointment.getArrivedAt(),
                appointment.getConsultationStartedAt(),
                appointment.getConsultationCompletedAt(),
                appointment.getCancelledAt()
        );
    }
}
