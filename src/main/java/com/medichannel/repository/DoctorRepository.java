package com.medichannel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.medichannel.entity.Doctor;

public interface DoctorRepository extends JpaRepository<Doctor, UUID> {

    Optional<Doctor> findByUserId(UUID userId);

    @Query("""
            SELECT d
            FROM Doctor d
            JOIN FETCH d.specialisation s
            JOIN FETCH d.user u
            WHERE u.id = :userId
            """)
    Optional<Doctor> findByUserIdWithSpecialisation(
            @Param("userId") UUID userId
    );

    Optional<Doctor> findByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumber(String registrationNumber);

    List<Doctor> findBySpecialisationId(UUID specialisationId);

    List<Doctor> findByAvailableTrue();

    List<Doctor> findBySpecialisationIdAndAvailableTrue(UUID specialisationId);

    @Query("""
            SELECT d
            FROM Doctor d
            JOIN FETCH d.specialisation s
            JOIN FETCH d.user u
            WHERE d.available = true
              AND s.active = true
              AND u.accountStatus = 'ACTIVE'
              AND u.emailVerified = true
            ORDER BY d.firstName, d.lastName
            """)
    List<Doctor> findPublicDirectory();

    @Query("""
            SELECT d
            FROM Doctor d
            JOIN FETCH d.specialisation s
            JOIN FETCH d.user u
            WHERE d.specialisation.id = :specialisationId
              AND d.available = true
              AND s.active = true
              AND u.accountStatus = 'ACTIVE'
              AND u.emailVerified = true
            ORDER BY d.firstName, d.lastName
            """)
    List<Doctor> findPublicDirectoryBySpecialisation(
            @Param("specialisationId") UUID specialisationId
    );

    @Query("""
            SELECT d
            FROM Doctor d
            JOIN FETCH d.specialisation s
            JOIN FETCH d.user u
            WHERE d.id = :doctorId
              AND d.available = true
              AND s.active = true
              AND u.accountStatus = 'ACTIVE'
              AND u.emailVerified = true
            """)
    Optional<Doctor> findPublicDoctorById(
            @Param("doctorId") UUID doctorId
    );
}
