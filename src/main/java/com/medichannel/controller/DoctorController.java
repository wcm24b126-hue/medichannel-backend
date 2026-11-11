package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.doctor.CreateDoctorRequest;
import com.medichannel.dto.doctor.DoctorAvailabilityRequest;
import com.medichannel.dto.doctor.DoctorCreatedResponse;
import com.medichannel.dto.doctor.DoctorResponse;
import com.medichannel.dto.doctor.UpdateDoctorRequest;
import com.medichannel.service.DoctorService;

@RestController
@RequestMapping("/api/v1/doctors")
public class DoctorController {

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    // Public directory. Optional specialisation filter.
    @GetMapping
    public ResponseEntity<List<DoctorResponse>> getDoctors(
            @RequestParam(required = false) UUID specialisationId
    ) {
        return ResponseEntity.ok(
                doctorService.getPublicDirectory(specialisationId)
        );
    }

    // Public doctor details.
    @GetMapping("/{id}")
    public ResponseEntity<DoctorResponse> getDoctor(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                doctorService.getPublicDoctor(id)
        );
    }

    // Admin/Manager creates the user account and doctor profile together.
    @PostMapping
    public ResponseEntity<DoctorCreatedResponse> createDoctor(
            @Valid @RequestBody CreateDoctorRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(doctorService.createDoctor(request));
    }

    // Admin/Manager updates professional details.
    @PutMapping("/{id}")
    public ResponseEntity<DoctorResponse> updateDoctor(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDoctorRequest request
    ) {
        return ResponseEntity.ok(
                doctorService.updateDoctor(id, request)
        );
    }

    // Admin/Manager changes availability.
    @PatchMapping("/{id}/availability")
    public ResponseEntity<DoctorResponse> updateAvailability(
            @PathVariable UUID id,
            @Valid @RequestBody DoctorAvailabilityRequest request
    ) {
        return ResponseEntity.ok(
                doctorService.updateAvailability(id, request)
        );
    }
}
