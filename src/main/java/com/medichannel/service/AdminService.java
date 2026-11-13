package com.medichannel.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.admin.AdminAppointmentResponse;
import com.medichannel.dto.admin.AdminPaymentResponse;
import com.medichannel.dto.admin.AdminStatsResponse;
import com.medichannel.dto.admin.AdminUserResponse;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Payment;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.NotificationType;
import com.medichannel.enums.PaymentTransactionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.PaymentRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

@Service
public class AdminService {

    private static final List<String> VALID_ROLES = List.of(
            "ADMIN",
            "MANAGER",
            "DOCTOR",
            "CUSTOMER"
    );

    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final PaymentRepository paymentRepository;
    private final AppointmentService appointmentService;
    private final NotificationService notificationService;
    private final PatientDataProtectionService dataProtectionService;

    public AdminService(
            UserRepository userRepository,
            DoctorRepository doctorRepository,
            PatientRepository patientRepository,
            AppointmentRepository appointmentRepository,
            PaymentRepository paymentRepository,
            AppointmentService appointmentService,
            NotificationService notificationService,
            PatientDataProtectionService dataProtectionService
    ) {
        this.userRepository = userRepository;
        this.doctorRepository = doctorRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.paymentRepository = paymentRepository;
        this.appointmentService = appointmentService;
        this.notificationService = notificationService;
        this.dataProtectionService = dataProtectionService;
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers(
            String role,
            String accountStatus
    ) {
        List<User> users;

        if (role == null || role.isBlank()) {
            users = userRepository.findAllByOrderByCreatedAtDesc();
        } else {
            String normalizedRole = role.trim().toUpperCase(Locale.ROOT);

            if (!VALID_ROLES.contains(normalizedRole)) {
                throw new IllegalArgumentException(
                        "Unknown user role."
                );
            }

            users = userRepository
                    .findAllByRole_NameOrderByCreatedAtDesc(normalizedRole);
        }

        String normalizedStatus = accountStatus == null
                || accountStatus.isBlank()
                        ? null
                        : accountStatus.trim().toUpperCase(Locale.ROOT);

        return users.stream()
                .filter(user -> normalizedStatus == null
                        || normalizedStatus.equals(user.getAccountStatus()))
                .map(this::toUserResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID userId) {
        return toUserResponse(findUser(userId));
    }

    @Transactional
    public AdminUserResponse activateUser(
            UUID actingAdminId,
            UUID userId
    ) {
        User user = findUser(userId);

        if (user.getId().equals(actingAdminId)
                && !"ACTIVE".equals(user.getAccountStatus())) {
            throw new ConflictException(
                    "Administrators cannot activate their own account."
            );
        }

        user.activateByAdministrator();

        notifyAccountChange(user, "Your account has been activated.");

        return toUserResponse(user);
    }

    @Transactional
    public AdminUserResponse suspendUser(
            UUID actingAdminId,
            UUID userId
    ) {
        User user = findUser(userId);
        preventSelfLockout(actingAdminId, user);

        user.suspend();

        notifyAccountChange(user, "Your account has been suspended.");

        return toUserResponse(user);
    }

    @Transactional
    public AdminUserResponse disableUser(
            UUID actingAdminId,
            UUID userId
    ) {
        User user = findUser(userId);
        preventSelfLockout(actingAdminId, user);

        user.disable();

        notifyAccountChange(user, "Your account has been disabled.");

        return toUserResponse(user);
    }

    @Transactional
    public AdminUserResponse unlockUser(UUID userId) {
        User user = findUser(userId);
        user.unlock();
        return toUserResponse(user);
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        long totalAppointments = appointmentRepository.count();
        long completedPayments = paymentRepository.countByStatus(
                PaymentTransactionStatus.COMPLETED
        );

        return new AdminStatsResponse(
                userRepository.count(),
                doctorRepository.count(),
                patientRepository.count(),
                totalAppointments,
                appointmentRepository.countByStatus(
                        AppointmentStatus.BOOKED
                ),
                appointmentRepository.countByStatus(
                        AppointmentStatus.COMPLETED
                ),
                appointmentRepository.countByStatus(
                        AppointmentStatus.CANCELLED
                ),
                appointmentRepository.countByStatus(
                        AppointmentStatus.NO_SHOW
                ),
                completedPayments,
                paymentRepository.sumAmountByStatus(
                        PaymentTransactionStatus.COMPLETED
                )
        );
    }

    @Transactional(readOnly = true)
    public List<AdminAppointmentResponse> getAppointments(
            UUID sessionId,
            UUID doctorId,
            AppointmentStatus status,
            LocalDate sessionDate
    ) {
        return appointmentService.getForOversight(
                sessionId,
                doctorId,
                status,
                sessionDate
        );
    }

    @Transactional(readOnly = true)
    public AdminAppointmentResponse getAppointment(UUID appointmentId) {
        return appointmentService.getForOversight(appointmentId);
    }

    @Transactional(readOnly = true)
    public List<AdminPaymentResponse> getPayments() {
        return paymentRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toPaymentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminPaymentResponse> getAppointmentPayments(
            UUID appointmentId
    ) {
        if (!appointmentRepository.existsById(appointmentId)) {
            throw new ResourceNotFoundException(
                    "Appointment was not found."
            );
        }

        return paymentRepository
                .findByAppointment_IdOrderByCreatedAtDesc(appointmentId)
                .stream()
                .map(this::toPaymentResponse)
                .toList();
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User account was not found."
                ));
    }

    private void preventSelfLockout(UUID actingAdminId, User target) {
        if (target.getId().equals(actingAdminId)) {
            throw new ConflictException(
                    "Administrators cannot suspend or disable their own account."
            );
        }

        if ("ADMIN".equals(target.getRole().getName())
                && userRepository.findAllByRole_NameOrderByCreatedAtDesc(
                        "ADMIN"
                ).stream()
                        .filter(admin -> "ACTIVE".equals(
                                admin.getAccountStatus()
                        ))
                        .count() <= 1) {
            throw new ConflictException(
                    "The last active administrator cannot be suspended or disabled."
            );
        }
    }

    private void notifyAccountChange(User user, String message) {
        notificationService.create(
                user.getId(),
                NotificationType.ACCOUNT_STATUS_CHANGED,
                "Account status updated",
                message,
                null
        );
    }

    private AdminUserResponse toUserResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole().getName(),
                user.getAccountStatus(),
                user.isEmailVerified(),
                user.getFailedLoginAttempts(),
                user.getLockedUntil(),
                user.getLastLoginAt(),
                user.getCreatedAt()
        );
    }

    private AdminPaymentResponse toPaymentResponse(Payment payment) {
        Appointment appointment = payment.getAppointment();

        String patientName;
        try {
            patientName = (dataProtectionService.decrypt(
                    appointment.getPatient().getFirstNameEncrypted()
            ) + " " + dataProtectionService.decrypt(
                    appointment.getPatient().getLastNameEncrypted()
            )).trim();
            if (patientName.isBlank()) {
                patientName = "Patient #" + appointment.getAppointmentNumber();
            }
        } catch (Exception e) {
            patientName = "Patient #" + appointment.getAppointmentNumber();
        }

        return new AdminPaymentResponse(
                payment.getId(),
                appointment.getId(),
                appointment.getPatient().getId(),
                patientName,
                payment.getAmount(),
                payment.getCurrency(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getPaymentReference(),
                payment.getPaidAt(),
                payment.getCreatedAt()
        );
    }
}
