package com.medichannel.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.Payment;
import com.medichannel.enums.PaymentTransactionStatus;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByAppointment_IdOrderByCreatedAtDesc(
            UUID appointmentId
    );

    boolean existsByAppointment_IdAndStatus(
            UUID appointmentId,
            PaymentTransactionStatus status
    );

    List<Payment> findAllByAppointment_Patient_User_IdOrderByCreatedAtDesc(
            UUID userId
    );

    List<Payment> findAllByOrderByCreatedAtDesc();

    long countByStatus(PaymentTransactionStatus status);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0)
            FROM Payment p
            WHERE p.status = :status
            """)
    BigDecimal sumAmountByStatus(
            @Param("status") PaymentTransactionStatus status
    );

    Optional<Payment> findByIdAndAppointment_Patient_User_Id(
            UUID paymentId,
            UUID userId
    );
}
