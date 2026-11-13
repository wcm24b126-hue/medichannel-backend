package com.medichannel.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.admin.AdminAppointmentResponse;
import com.medichannel.dto.admin.AdminPaymentResponse;
import com.medichannel.dto.admin.AdminStatsResponse;
import com.medichannel.dto.admin.AdminUserResponse;
import com.medichannel.dto.admin.CreateManagerRequest;
import com.medichannel.dto.admin.ResetStaffPasswordRequest;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.service.AdminService;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> listUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(adminService.listUsers(role, status));
    }

    @PostMapping("/managers")
    public ResponseEntity<AdminUserResponse> createManager(
            @AuthenticationPrincipal Jwt jwt,
            @jakarta.validation.Valid @RequestBody CreateManagerRequest request
    ) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(adminService.createManager(actingAdminId(jwt), request));
    }

    @PostMapping("/users/{userId}/reset-password")
    public ResponseEntity<AdminUserResponse> resetStaffPassword(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @jakarta.validation.Valid
            @RequestBody ResetStaffPasswordRequest request
    ) {
        return ResponseEntity.ok(
                adminService.resetStaffPassword(
                        actingAdminId(jwt),
                        userId,
                        request
                )
        );
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<AdminUserResponse> getUser(
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(adminService.getUser(userId));
    }

    @PatchMapping("/users/{userId}/activate")
    public ResponseEntity<AdminUserResponse> activateUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(
                adminService.activateUser(actingAdminId(jwt), userId)
        );
    }

    @PatchMapping("/users/{userId}/suspend")
    public ResponseEntity<AdminUserResponse> suspendUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(
                adminService.suspendUser(actingAdminId(jwt), userId)
        );
    }

    @PatchMapping("/users/{userId}/disable")
    public ResponseEntity<AdminUserResponse> disableUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(
                adminService.disableUser(actingAdminId(jwt), userId)
        );
    }

    @PatchMapping("/users/{userId}/unlock")
    public ResponseEntity<AdminUserResponse> unlockUser(
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(adminService.unlockUser(userId));
    }

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/appointments")
    public ResponseEntity<List<AdminAppointmentResponse>> getAppointments(
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        return ResponseEntity.ok(
                adminService.getAppointments(
                        sessionId,
                        doctorId,
                        status,
                        date
                )
        );
    }

    @GetMapping("/appointments/{appointmentId}")
    public ResponseEntity<AdminAppointmentResponse> getAppointment(
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                adminService.getAppointment(appointmentId)
        );
    }

    @GetMapping("/payments")
    public ResponseEntity<List<AdminPaymentResponse>> getPayments() {
        return ResponseEntity.ok(adminService.getPayments());
    }

    @GetMapping("/appointments/{appointmentId}/payments")
    public ResponseEntity<List<AdminPaymentResponse>> getAppointmentPayments(
            @PathVariable UUID appointmentId
    ) {
        return ResponseEntity.ok(
                adminService.getAppointmentPayments(appointmentId)
        );
    }

    private UUID actingAdminId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
