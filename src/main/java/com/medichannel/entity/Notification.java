package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.NotificationType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_notifications_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "appointment_id",
        foreignKey = @ForeignKey(name = "fk_notifications_appointment")
    )
    private Appointment appointment;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(
            User user,
            Appointment appointment,
            NotificationType type,
            String title,
            String message
    ) {
        if (user == null) {
            throw new IllegalArgumentException(
                    "Notification recipient is required."
            );
        }

        if (type == null) {
            throw new IllegalArgumentException(
                    "Notification type is required."
            );
        }

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "Notification title is required."
            );
        }

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "Notification message is required."
            );
        }

        this.user = user;
        this.appointment = appointment;
        this.type = type;
        this.title = title.trim();
        this.message = message.trim();
        this.read = false;
    }

    public void markRead(Instant readTime) {
        if (readTime == null) {
            throw new IllegalArgumentException(
                    "Read time is required."
            );
        }

        if (!read) {
            this.read = true;
            this.readAt = readTime;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
