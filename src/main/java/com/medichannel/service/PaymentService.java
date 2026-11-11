package com.medichannel.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.payment.PaymentResponse;
import com.medichannel.dto.payment.RecordPaymentRequest;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Payment;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.NotificationType;
import com.medichannel.enums.PaymentStatus;
import com.medichannel.enums.PaymentTransactionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.PaymentRepository;
import com.medichannel.repository.UserRepository;

/**
 * Records appointment payments.
 *
 * IMPORTANT: MediChannel does not integrate a real payment gateway.
 * A CARD payment recorded through this service is a simulated
 * payment record. No card number, CVV or other credential is
 * collected or stored. A real gateway can later populate this
 * table through a verified callback without changing the API
 * contract.
 */
@Service
public class PaymentService {

    private static final String PAYMENT_PREFIX = "PAY-";

    private final PaymentRepository paymentRepository;
    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;
    private final AppointmentAccessPolicy accessPolicy;
    private final NotificationService notificationService;
    private final Clock clock;

    public PaymentService(
            PaymentRepository paymentRepository,
            AppointmentRepository appointmentRepository,
            UserRepository userRepository,
            AppointmentAccessPolicy accessPolicy,
            NotificationService notificationService,
            Clock clock
    ) {
        this.paymentRepository = paymentRepository;
        this.appointmentRepository = appointmentRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Transactional
    public PaymentResponse recordPayment(
            UUID actorUserId,
            String actorRole,
            UUID appointmentId,
            RecordPaymentRequest request
    ) {
        if (request == null || request.method() == null) {
            throw new IllegalArgumentException(
                    "Payment method is required."
            );
        }

        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        boolean patientOwner = accessPolicy.isPatientOwner(
                appointment,
                actorUserId
        );

        if (!patientOwner && !accessPolicy.isStaff(actorRole)) {
            throw new ForbiddenOperationException(
                    "You are not authorized to record a payment for this appointment."
            );
        }

        if (appointment.getStatus() == AppointmentStatus.CANCELLED
                || appointment.getStatus() == AppointmentStatus.NO_SHOW) {
            throw new ConflictException(
                    "Payments cannot be recorded for a cancelled or missed appointment."
            );
        }

        if (appointment.getPaymentStatus() == PaymentStatus.PAID
                || paymentRepository.existsByAppointment_IdAndStatus(
                        appointmentId,
                        PaymentTransactionStatus.COMPLETED
                )) {
            throw new ConflictException(
                    "This appointment has already been paid."
            );
        }

        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User account was not found."
                ));

        Payment payment = new Payment(
                appointment,
                appointment.getSession().getFeeLkr(),
                request.method(),
                PAYMENT_PREFIX + UUID.randomUUID(),
                actor,
                clock.instant()
        );

        try {
            Payment saved = paymentRepository.saveAndFlush(payment);

            appointment.markPaid(request.method());
            appointmentRepository.saveAndFlush(appointment);

            notifyParticipants(appointment, saved);

            return toResponse(saved);

        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "This appointment has already been paid."
            );
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getAppointmentPayments(
            UUID actorUserId,
            String actorRole,
            UUID appointmentId
    ) {
        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment was not found."
                ));

        accessPolicy.requireReadAccess(
                appointment,
                actorUserId,
                actorRole
        );

        return paymentRepository
                .findByAppointment_IdOrderByCreatedAtDesc(appointmentId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getMyPayments(UUID userId) {
        return paymentRepository
                .findAllByAppointment_Patient_User_IdOrderByCreatedAtDesc(
                        userId
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getAllPayments() {
        return paymentRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private void notifyParticipants(
            Appointment appointment,
            Payment payment
    ) {
        UUID patientUserId = appointment.getPatient().getUser().getId();
        UUID doctorUserId = appointment.getSession().getDoctor().getUser().getId();

        String reference = payment.getPaymentReference();

        notificationService.create(
                patientUserId,
                NotificationType.PAYMENT_RECEIVED,
                "Payment received",
                "Your payment of LKR "
                        + payment.getAmount().toPlainString()
                        + " was recorded. Reference: "
                        + reference,
                appointment.getId()
        );

        notificationService.create(
                doctorUserId,
                NotificationType.PAYMENT_RECEIVED,
                "Appointment payment received",
                "A payment was recorded for appointment #"
                        + appointment.getAppointmentNumber()
                        + ". Reference: " + reference,
                appointment.getId()
        );
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getAppointment().getId(),
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
