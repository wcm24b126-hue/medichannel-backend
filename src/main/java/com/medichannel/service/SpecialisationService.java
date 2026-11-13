package com.medichannel.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import com.medichannel.config.CacheConfig;

import com.medichannel.dto.specialisation.SpecialisationRequest;
import com.medichannel.dto.specialisation.SpecialisationResponse;
import com.medichannel.entity.Specialisation;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.SpecialisationRepository;

@Service
public class SpecialisationService {

    private final SpecialisationRepository specialisationRepository;

    public SpecialisationService(
            SpecialisationRepository specialisationRepository
    ) {
        this.specialisationRepository = specialisationRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.PUBLIC_SPECIALISATIONS, key = "'active'")
    public List<SpecialisationResponse> getActiveSpecialisations() {
        return specialisationRepository.findByActiveTrue()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SpecialisationResponse getActiveSpecialisation(UUID id) {
        Specialisation specialisation = specialisationRepository.findById(id)
                .filter(Specialisation::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Specialisation not found."
                ));

        return toResponse(specialisation);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PUBLIC_SPECIALISATIONS, allEntries = true, beforeInvocation = true)
    public SpecialisationResponse create(SpecialisationRequest request) {
        String name = normalizeName(request.name());

        if (specialisationRepository.existsByName(name)) {
            throw new ConflictException(
                    "A specialisation with this name already exists."
            );
        }

        Specialisation specialisation = new Specialisation(
                name,
                normalizeDescription(request.description())
        );

        return toResponse(
                specialisationRepository.saveAndFlush(specialisation)
        );
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PUBLIC_SPECIALISATIONS, allEntries = true, beforeInvocation = true)
    public SpecialisationResponse update(
            UUID id,
            SpecialisationRequest request
    ) {
        Specialisation specialisation = findById(id);
        String name = normalizeName(request.name());

        specialisationRepository.findByName(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "A specialisation with this name already exists."
                    );
                });

        specialisation.updateDetails(
                name,
                normalizeDescription(request.description())
        );

        return toResponse(specialisation);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PUBLIC_SPECIALISATIONS, allEntries = true, beforeInvocation = true)
    public SpecialisationResponse deactivate(UUID id) {
        Specialisation specialisation = findById(id);
        specialisation.deactivate();
        return toResponse(specialisation);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PUBLIC_SPECIALISATIONS, allEntries = true, beforeInvocation = true)
    public SpecialisationResponse activate(UUID id) {
        Specialisation specialisation = findById(id);
        specialisation.activate();
        return toResponse(specialisation);
    }

    private Specialisation findById(UUID id) {
        return specialisationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Specialisation not found."
                ));
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Specialisation name is required."
            );
        }

        return name.trim().replaceAll("\\s+", " ");
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        return description.trim();
    }

    private SpecialisationResponse toResponse(
            Specialisation specialisation
    ) {
        return new SpecialisationResponse(
                specialisation.getId(),
                specialisation.getName(),
                specialisation.getDescription(),
                specialisation.isActive(),
                specialisation.getCreatedAt()
        );
    }
}
