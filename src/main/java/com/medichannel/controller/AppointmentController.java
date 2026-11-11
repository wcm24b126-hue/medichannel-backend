package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.dto.appointment.AppointmentResponse;
import com.medichannel.service.AppointmentService;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(
            AppointmentService appointmentService
    ) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AppointmentBookingRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        AppointmentResponse response =
                appointmentService.book(userId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                appointmentService.getMyAppointments(userId)
        );
    }

    @GetMapping("/{appointmentId}")
    public ResponseEntity<AppointmentResponse> getMyAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                appointmentService.getMyAppointment(
                        userId,
                        appointmentId
                )
        );
    }

    @PatchMapping("/{appointmentId}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                appointmentService.cancelMyAppointment(
                        userId,
                        appointmentId
                )
        );
    }

    @PatchMapping("/{appointmentId}/check-in")
    public ResponseEntity<AppointmentResponse> checkIn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                appointmentService.checkIn(userId, appointmentId)
        );
    }
}
