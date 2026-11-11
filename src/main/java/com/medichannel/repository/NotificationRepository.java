package com.medichannel.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.Notification;

@Repository
public interface NotificationRepository
        extends JpaRepository<Notification, UUID> {

    List<Notification> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    long countByUser_IdAndReadFalse(UUID userId);

    Optional<Notification> findByIdAndUser_Id(
            UUID notificationId,
            UUID userId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
            SET n.read = true,
                n.readAt = :readAt
            WHERE n.user.id = :userId
              AND n.read = false
            """)
    int markAllRead(
            @Param("userId") UUID userId,
            @Param("readAt") Instant readAt
    );
}
