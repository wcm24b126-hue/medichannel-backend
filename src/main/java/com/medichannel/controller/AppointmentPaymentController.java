package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.payment.PaymentResponse;
import com.medichannel.dto.payment.RecordPaymentRequest;
import com.medichannel.service.PaymentService;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentPaymentController {

    private final PaymentService paymentService;

    public AppointmentPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // Record a (simulated) payment for an appointment.
    @PostMapping("/{appointmentId}/payments")
    public ResponseEntity<PaymentResponse> recordPayment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody RecordPaymentRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String role = jwt.getClaimAsString("role");

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(paymentService.recordPayment(
                        userId,
                        role,
                        appointmentId,
                        request
                ));
    }

    // Payment history for one appointment.
    @GetMapping("/{appointmentId}/payments")
    public ResponseEntity<List<PaymentResponse>> getAppointmentPayments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String role = jwt.getClaimAsString("role");

        return ResponseEntity.ok(
                paymentService.getAppointmentPayments(
                        userId,
                        role,
                        appointmentId
                )
        );
    }
}
