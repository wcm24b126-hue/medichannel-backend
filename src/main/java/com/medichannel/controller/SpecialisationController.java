package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.specialisation.SpecialisationRequest;
import com.medichannel.dto.specialisation.SpecialisationResponse;
import com.medichannel.service.SpecialisationService;

@RestController
@RequestMapping("/api/v1/specialisations")
public class SpecialisationController {

    private final SpecialisationService specialisationService;

    public SpecialisationController(
            SpecialisationService specialisationService
    ) {
        this.specialisationService = specialisationService;
    }

    // Public list of active specialisations.
    @GetMapping
    public ResponseEntity<List<SpecialisationResponse>> getSpecialisations() {
        return ResponseEntity.ok(
                specialisationService.getActiveSpecialisations()
        );
    }

    // Public details of one active specialisation.
    @GetMapping("/{id}")
    public ResponseEntity<SpecialisationResponse> getSpecialisation(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                specialisationService.getActiveSpecialisation(id)
        );
    }

    // Admin/Manager creates a specialisation.
    @PostMapping
    public ResponseEntity<SpecialisationResponse> createSpecialisation(
            @Valid @RequestBody SpecialisationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(specialisationService.create(request));
    }

    // Admin/Manager updates a specialisation.
    @PutMapping("/{id}")
    public ResponseEntity<SpecialisationResponse> updateSpecialisation(
            @PathVariable UUID id,
            @Valid @RequestBody SpecialisationRequest request
    ) {
        return ResponseEntity.ok(
                specialisationService.update(id, request)
        );
    }

    // Admin/Manager deactivates a specialisation.
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<SpecialisationResponse> deactivateSpecialisation(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                specialisationService.deactivate(id)
        );
    }

    // Admin/Manager reactivates a specialisation.
    @PatchMapping("/{id}/activate")
    public ResponseEntity<SpecialisationResponse> activateSpecialisation(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                specialisationService.activate(id)
        );
    }
}
