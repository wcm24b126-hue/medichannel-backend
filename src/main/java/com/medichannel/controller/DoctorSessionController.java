package com.medichannel.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.session.DoctorSessionRequest;
import com.medichannel.dto.session.DoctorSessionResponse;
import com.medichannel.dto.session.SessionDelayRequest;
import com.medichannel.enums.ConsultationType;
import com.medichannel.service.DoctorSessionService;

@RestController
@RequestMapping("/api/v1/sessions")
public class DoctorSessionController {

    private final DoctorSessionService doctorSessionService;

    public DoctorSessionController(
            DoctorSessionService doctorSessionService
    ) {
        this.doctorSessionService = doctorSessionService;
    }

    // Public discovery of future open sessions with remaining capacity.
    @GetMapping
    public ResponseEntity<List<DoctorSessionResponse>> getPublicSessions(
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) UUID specialisationId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ConsultationType consultationType
    ) {
        return ResponseEntity.ok(
                doctorSessionService.getPublicSessions(
                        doctorId,
                        specialisationId,
                        date,
                        q,
                        consultationType
                )
        );
    }

    // Admin/Manager creates a doctor session.
    @PostMapping
    public ResponseEntity<DoctorSessionResponse> createSession(
            @Valid @RequestBody DoctorSessionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(doctorSessionService.createSession(request));
    }

    // Authenticated users can view session details.
    @GetMapping("/{sessionId}")
    public ResponseEntity<DoctorSessionResponse> getSession(
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(
                doctorSessionService.getSession(sessionId)
        );
    }

    // Admin/Manager updates a scheduled or open session.
    @PutMapping("/{sessionId}")
    public ResponseEntity<DoctorSessionResponse> updateSession(
            @PathVariable UUID sessionId,
            @Valid @RequestBody DoctorSessionRequest request
    ) {
        return ResponseEntity.ok(
                doctorSessionService.updateSession(sessionId, request)
        );
    }

    @PatchMapping("/{sessionId}/open")
    public ResponseEntity<DoctorSessionResponse> openSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(
                doctorSessionService.openSession(
                        sessionId,
                        UUID.fromString(jwt.getSubject()),
                        jwt.getClaimAsString("role")
                )
        );
    }

    @PatchMapping("/{sessionId}/start")
    public ResponseEntity<DoctorSessionResponse> startSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(
                doctorSessionService.startSession(
                        sessionId,
                        UUID.fromString(jwt.getSubject()),
                        jwt.getClaimAsString("role")
                )
        );
    }

    @PatchMapping("/{sessionId}/complete")
    public ResponseEntity<DoctorSessionResponse> completeSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(
                doctorSessionService.completeSession(
                        sessionId,
                        UUID.fromString(jwt.getSubject()),
                        jwt.getClaimAsString("role")
                )
        );
    }

    @PatchMapping("/{sessionId}/cancel")
    public ResponseEntity<DoctorSessionResponse> cancelSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(
                doctorSessionService.cancelSession(
                        sessionId,
                        UUID.fromString(jwt.getSubject()),
                        jwt.getClaimAsString("role")
                )
        );
    }

    @PatchMapping("/{sessionId}/delay")
    public ResponseEntity<DoctorSessionResponse> updateDelay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SessionDelayRequest request
    ) {
        return ResponseEntity.ok(
                doctorSessionService.updateDelay(
                        sessionId,
                        request.delayMinutes(),
                        UUID.fromString(jwt.getSubject()),
                        jwt.getClaimAsString("role")
                )
        );
    }
}
