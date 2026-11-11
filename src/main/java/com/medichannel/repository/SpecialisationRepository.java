package com.medichannel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.medichannel.entity.Specialisation;

public interface SpecialisationRepository
        extends JpaRepository<Specialisation, UUID> {

    Optional<Specialisation> findByName(String name);

    boolean existsByName(String name);

    List<Specialisation> findByActiveTrue();
}
