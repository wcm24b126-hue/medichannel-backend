package com.medichannel.dto.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentTransactionStatus;

public record AdminPaymentResponse(

        UUID paymentId,
        UUID appointmentId,
        UUID patientId,
        String patientName,
        BigDecimal amount,
        String currency,
        PaymentMethod method,
        PaymentTransactionStatus status,
        String paymentReference,
        Instant paidAt,
        Instant createdAt

) {
}
