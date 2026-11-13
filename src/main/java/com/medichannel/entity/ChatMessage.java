package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "appointment_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_chat_messages_appointment")
    )
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "sender_user_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_chat_messages_sender")
    )
    private User senderUser;

    @Column(name = "sender_role", nullable = false, length = 20)
    private String senderRole;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    protected ChatMessage() {
    }

    public ChatMessage(
            Appointment appointment,
            User senderUser,
            String senderRole,
            String content
    ) {
        if (appointment == null) {
            throw new IllegalArgumentException(
                    "Chat appointment is required."
            );
        }

        if (senderUser == null) {
            throw new IllegalArgumentException(
                    "Chat sender is required."
            );
        }

        this.appointment = appointment;
        this.senderUser = senderUser;
        this.senderRole = senderRole;
        this.content = content;
    }

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = Instant.now();
        }
    }

    public void setSentAt(Instant sentAt) {
        if (this.sentAt != null) {
            throw new IllegalStateException(
                    "Chat message send time cannot be changed."
            );
        }

        this.sentAt = sentAt;
    }

    public void setContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Chat content cannot be empty.");
        }
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public User getSenderUser() {
        return senderUser;
    }

    public String getSenderRole() {
        return senderRole;
    }

    public String getContent() {
        return content;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
