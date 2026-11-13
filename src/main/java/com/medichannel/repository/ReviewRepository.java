package com.medichannel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.Review;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Optional<Review> findByAppointment_Id(UUID appointmentId);

    boolean existsByAppointment_Id(UUID appointmentId);

    List<Review> findByDoctor_IdAndModerationStatusOrderByCreatedAtDesc(
            UUID doctorId,
            String moderationStatus
    );

    List<Review> findAllByOrderByCreatedAtDesc();

    List<Review> findByModerationStatusOrderByCreatedAtDesc(
            String moderationStatus
    );

    List<Review> findByPatient_User_IdOrderByCreatedAtDesc(UUID userId);

    long countByDoctor_Id(UUID doctorId);

    @Query("""
            SELECT AVG(r.rating)
            FROM Review r
            WHERE r.doctor.id = :doctorId
              AND r.moderationStatus = 'APPROVED'
            """)
    Double averageRatingByDoctorId(
            @Param("doctorId") UUID doctorId
    );
}
