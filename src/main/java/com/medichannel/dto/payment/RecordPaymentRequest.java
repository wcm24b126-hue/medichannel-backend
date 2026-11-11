package com.medichannel.dto.payment;

import com.medichannel.enums.PaymentMethod;

import jakarta.validation.constraints.NotNull;

public record RecordPaymentRequest(

        @NotNull(message = "Payment method is required.")
        PaymentMethod method

) {
}
