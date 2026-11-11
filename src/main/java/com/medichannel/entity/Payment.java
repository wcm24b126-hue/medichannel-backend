package com.medichannel.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentTransactionStatus;

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
import jakarta.persistence.Table;

@Entity
@Table(name = "payments")
public class Payment {

    private static final String DEFAULT_CURRENCY = "LKR";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "appointment_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_payments_appointment")
    )
    private Appointment appointment;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = DEFAULT_CURRENCY;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentTransactionStatus status =
            PaymentTransactionStatus.COMPLETED;

    @Column(
        name = "payment_reference",
        nullable = false,
        unique = true,
        updatable = false,
        length = 64
    )
    private String paymentReference;

    @Column(name = "paid_at")
    private Instant paidAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "recorded_by",
        foreignKey = @ForeignKey(name = "fk_payments_recorded_by")
    )
    private User recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(
            Appointment appointment,
            BigDecimal amount,
            PaymentMethod method,
            String paymentReference,
            User recordedBy,
            Instant paidAt
    ) {
        if (appointment == null) {
            throw new IllegalArgumentException(
                    "Payment appointment is required."
            );
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Payment amount must not be negative."
            );
        }

        if (method == null) {
            throw new IllegalArgumentException(
                    "Payment method is required."
            );
        }

        if (paymentReference == null || paymentReference.isBlank()) {
            throw new IllegalArgumentException(
                    "Payment reference is required."
            );
        }

        if (paidAt == null) {
            throw new IllegalArgumentException(
                    "Payment time is required."
            );
        }

        this.appointment = appointment;
        this.amount = amount;
        this.method = method;
        this.paymentReference = paymentReference;
        this.recordedBy = recordedBy;
        this.paidAt = paidAt;
        this.status = PaymentTransactionStatus.COMPLETED;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentTransactionStatus getStatus() {
        return status;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public User getRecordedBy() {
        return recordedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
