package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.dto.appointment.AppointmentDelayRequest;
import com.medichannel.dto.appointment.AppointmentResponse;
import com.medichannel.dto.appointment.CancelAppointmentRequest;
import com.medichannel.dto.common.PagedResponse;
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

    @GetMapping("/me/active")
    public ResponseEntity<List<AppointmentResponse>> getMyActiveAppointments(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                appointmentService.getMyActiveAppointments(userId)
        );
    }

    @GetMapping("/me/history")
    public ResponseEntity<PagedResponse<AppointmentResponse>> getMyAppointmentHistory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                appointmentService.getMyAppointmentHistory(userId, page, size)
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
            @PathVariable UUID appointmentId,
            @Valid @RequestBody CancelAppointmentRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String role = jwt.getClaimAsString("role");
        String reason = request.reason();

        return ResponseEntity.ok(
                appointmentService.cancelAppointment(
                        userId,
                        role,
                        appointmentId,
                        reason
                )
        );
    }

    @PatchMapping("/{appointmentId}/delay")
    public ResponseEntity<AppointmentResponse> delayAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody AppointmentDelayRequest request
    ) {
        return ResponseEntity.ok(
                appointmentService.delayAppointmentQueue(
                        UUID.fromString(jwt.getSubject()),
                        jwt.getClaimAsString("role"),
                        appointmentId,
                        request.delayMinutes()
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

    /**
     * Clear a closed appointment from the patient's own history view. The
     * clinical and payment records are retained.
     */
    @PatchMapping("/{appointmentId}/hide")
    public ResponseEntity<Void> hideAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            HttpServletRequest request
    ) {
        appointmentService.hideMyAppointment(
                UUID.fromString(jwt.getSubject()),
                appointmentId,
                clientIp(request)
        );
        return ResponseEntity.noContent().build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
