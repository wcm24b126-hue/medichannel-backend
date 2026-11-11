package com.medichannel.controller;

import java.util.UUID;

import com.medichannel.dto.patient.PatientProfileRequest;
import com.medichannel.dto.patient.PatientProfileResponse;
import com.medichannel.service.PatientService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping("/me")
    public ResponseEntity<PatientProfileResponse> createMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PatientProfileRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        PatientProfileResponse response =
                patientService.createProfile(userId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<PatientProfileResponse> getMyProfile(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                patientService.getMyProfile(userId)
        );
    }

    @PutMapping("/me")
    public ResponseEntity<PatientProfileResponse> updateMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PatientProfileRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                patientService.updateMyProfile(userId, request)
        );
    }
}
