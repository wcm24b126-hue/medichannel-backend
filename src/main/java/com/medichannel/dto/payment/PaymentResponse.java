package com.medichannel.dto.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentTransactionStatus;

public record PaymentResponse(

        UUID paymentId,
        UUID appointmentId,
        BigDecimal amount,
        String currency,
        PaymentMethod method,
        PaymentTransactionStatus status,
        String paymentReference,
        Instant paidAt,
        Instant createdAt

) {
}
