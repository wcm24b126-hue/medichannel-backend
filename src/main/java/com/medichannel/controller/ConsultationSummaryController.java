package com.medichannel.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.consultation.ConsultationSummaryRequest;
import com.medichannel.dto.consultation.ConsultationSummaryResponse;
import com.medichannel.service.ConsultationSummaryService;

@RestController
@RequestMapping("/api/v1/appointments")
public class ConsultationSummaryController {

    private final ConsultationSummaryService summaryService;

    public ConsultationSummaryController(
            ConsultationSummaryService summaryService
    ) {
        this.summaryService = summaryService;
    }

    // Only the assigned doctor may publish a summary.
    @PostMapping("/{appointmentId}/summary")
    public ResponseEntity<ConsultationSummaryResponse> createSummary(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody ConsultationSummaryRequest request
    ) {
        UUID doctorUserId = UUID.fromString(jwt.getSubject());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(summaryService.create(
                        doctorUserId,
                        appointmentId,
                        request
                ));
    }

    @PutMapping("/{appointmentId}/summary")
    public ResponseEntity<ConsultationSummaryResponse> updateSummary(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody ConsultationSummaryRequest request
    ) {
        UUID doctorUserId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                summaryService.update(
                        doctorUserId,
                        appointmentId,
                        request
                )
        );
    }

    // The patient, the assigned doctor and staff can read a summary.
    @GetMapping("/{appointmentId}/summary")
    public ResponseEntity<ConsultationSummaryResponse> getSummary(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String role = jwt.getClaimAsString("role");

        return ResponseEntity.ok(
                summaryService.get(userId, role, appointmentId)
        );
    }
}
