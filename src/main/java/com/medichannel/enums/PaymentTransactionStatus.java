package com.medichannel.enums;

/**
 * Lifecycle of a recorded payment attempt.
 *
 * This is deliberately separate from the appointment-level
 * {@link PaymentStatus}: an appointment is either PENDING or PAID,
 * while an individual transaction can be pending, completed,
 * failed or refunded.
 */
public enum PaymentTransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED
}
