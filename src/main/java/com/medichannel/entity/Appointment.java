package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "appointments",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_appointment_session_number",
            columnNames = {"session_id", "appointment_number"}
        ),
        @UniqueConstraint(
            name = "uq_appointment_booking_reference",
            columnNames = {"booking_reference"}
        )
    }
)
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "session_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointments_session")
    )
    private DoctorSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "patient_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointments_patient")
    )
    private Patient patient;

    /*
     * PostgreSQL assigns this value through the
     * trg_assign_appointment_number BEFORE INSERT trigger.
     *
     * Hibernate retrieves the generated value after insertion.
     * Never assign this field from application code.
     */
    @Generated(event = EventType.INSERT)
    @Column(
        name = "appointment_number",
        nullable = false,
        updatable = false
    )
    private Integer appointmentNumber;

    @Column(
        name = "booking_reference",
        nullable = false,
        unique = true,
        updatable = false
    )
    private UUID bookingReference;

    @Column(
        name = "reason_encrypted",
        nullable = false,
        columnDefinition = "TEXT"
    )
    private String reasonEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AppointmentStatus status = AppointmentStatus.BOOKED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "acceptance_status", nullable = false, length = 20)
    private String acceptanceStatus = "PENDING";

    @Column(name = "doctor_accepted_at")
    private Instant doctorAcceptedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "consultation_started_at")
    private Instant consultationStartedAt;

    @Column(name = "consultation_completed_at")
    private Instant consultationCompletedAt;

    protected Appointment() {
    }

    public Appointment(
        DoctorSession session,
        Patient patient,
        String reasonEncrypted
    ) {
        if (session == null) {
            throw new IllegalArgumentException(
                "Appointment session is required."
            );
        }

        if (patient == null) {
            throw new IllegalArgumentException(
                "Appointment patient is required."
            );
        }

        if (reasonEncrypted == null || reasonEncrypted.isBlank()) {
            throw new IllegalArgumentException(
                "Appointment reason is required."
            );
        }

        this.session = session;
        this.patient = patient;
        this.reasonEncrypted = reasonEncrypted;
        this.bookingReference = UUID.randomUUID();
        this.status = AppointmentStatus.BOOKED;
        this.paymentStatus = PaymentStatus.PENDING;
    }

    public void cancel(Instant cancellationTime) {
        if (status != AppointmentStatus.BOOKED) {
            throw new IllegalStateException(
                "Only booked appointments can be cancelled."
            );
        }

        if (paymentStatus != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                "Paid appointments cannot be cancelled through this operation."
            );
        }

        if (cancellationTime == null) {
            throw new IllegalArgumentException(
                "Cancellation time is required."
            );
        }

        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = cancellationTime;
    }

    public void cancelByStaff(Instant cancellationTime) {
        if (status == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException("Appointment is already cancelled.");
        }
        if (status == AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Completed appointments cannot be cancelled.");
        }
        if (cancellationTime == null) {
            throw new IllegalArgumentException("Cancellation time is required.");
        }
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = cancellationTime;
    }

    public void acceptByDoctor(Instant time) {
        if (status == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled appointments cannot be accepted.");
        }
        this.acceptanceStatus = "ACCEPTED";
        this.doctorAcceptedAt = time;
    }

    public void declineByDoctor(Instant time) {
        if (status == AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Completed appointments cannot be declined.");
        }
        this.acceptanceStatus = "DECLINED";
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = time;
    }

    public String getAcceptanceStatus() {
        return acceptanceStatus;
    }

    public Instant getDoctorAcceptedAt() {
        return doctorAcceptedAt;
    }

    public void markPaid(PaymentMethod method) {
        if (status == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException(
                "Cancelled appointments cannot be paid."
            );
        }

        if (paymentStatus == PaymentStatus.PAID) {
            throw new IllegalStateException(
                "Appointment has already been paid."
            );
        }

        if (method == null) {
            throw new IllegalArgumentException(
                "Payment method is required."
            );
        }

        this.paymentMethod = method;
        this.paymentStatus = PaymentStatus.PAID;
    }

    public void markArrived(Instant arrivalTime) {
        if (status != AppointmentStatus.BOOKED) {
            throw new IllegalStateException(
                "Only booked appointments can be marked as arrived."
            );
        }

        if (arrivalTime == null) {
            throw new IllegalArgumentException(
                "Arrival time is required."
            );
        }

        this.arrivedAt = arrivalTime;
        status = AppointmentStatus.ARRIVED;
    }

    public void startConsultation(Instant startTime) {
        if (status != AppointmentStatus.BOOKED
                && status != AppointmentStatus.ARRIVED) {
            throw new IllegalStateException(
                "Appointment cannot enter consultation from its current status."
            );
        }

        if (startTime == null) {
            throw new IllegalArgumentException(
                "Consultation start time is required."
            );
        }

        if (status == AppointmentStatus.BOOKED) {
            // The doctor may start without a separate check-in step.
            this.arrivedAt = startTime;
        }

        this.consultationStartedAt = startTime;
        status = AppointmentStatus.IN_CONSULTATION;
    }

    public void complete(Instant completionTime) {
        if (status != AppointmentStatus.IN_CONSULTATION) {
            throw new IllegalStateException(
                "Only appointments in consultation can be completed."
            );
        }

        if (completionTime == null) {
            throw new IllegalArgumentException(
                "Consultation completion time is required."
            );
        }

        if (consultationStartedAt == null
                || completionTime.isBefore(consultationStartedAt)) {
            throw new IllegalArgumentException(
                "Completion time cannot be before the consultation start."
            );
        }

        this.consultationCompletedAt = completionTime;
        status = AppointmentStatus.COMPLETED;
    }

    public void markNoShow() {
        if (status != AppointmentStatus.BOOKED
                && status != AppointmentStatus.ARRIVED) {
            throw new IllegalStateException(
                "This appointment cannot be marked as a no-show."
            );
        }

        status = AppointmentStatus.NO_SHOW;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;

        if (bookingReference == null) {
            bookingReference = UUID.randomUUID();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public DoctorSession getSession() {
        return session;
    }

    public Patient getPatient() {
        return patient;
    }

    public Integer getAppointmentNumber() {
        return appointmentNumber;
    }

    public UUID getBookingReference() {
        return bookingReference;
    }

    public String getReasonEncrypted() {
        return reasonEncrypted;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getConsultationStartedAt() {
        return consultationStartedAt;
    }

    public Instant getConsultationCompletedAt() {
        return consultationCompletedAt;
    }
}
