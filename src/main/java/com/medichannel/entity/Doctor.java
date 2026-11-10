package com.medichannel.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "specialisation_id", nullable = false)
    private Specialisation specialisation;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "registration_number", nullable = false, unique = true, length = 100)
    private String registrationNumber;

    @Column(name = "qualification", length = 255)
    private String qualification;

    @Column(name = "biography", columnDefinition = "TEXT")
    private String biography;

    @Column(name = "consultation_fee", precision = 10, scale = 2)
    private BigDecimal consultationFee;

    @Column(name = "is_available", nullable = false)
    private boolean available = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Doctor() {
    }

    public Doctor(
            User user,
            Specialisation specialisation,
            String firstName,
            String lastName,
            String registrationNumber,
            String qualification,
            String biography,
            BigDecimal consultationFee
    ) {
        this.user = user;
        this.specialisation = specialisation;
        this.firstName = firstName;
        this.lastName = lastName;
        this.registrationNumber = registrationNumber;
        this.qualification = qualification;
        this.biography = biography;
        this.consultationFee = consultationFee;
    }

    public void updateProfile(
            Specialisation specialisation,
            String firstName,
            String lastName,
            String registrationNumber,
            String qualification,
            String biography,
            BigDecimal consultationFee
    ) {
        this.specialisation = specialisation;
        this.firstName = firstName;
        this.lastName = lastName;
        this.registrationNumber = registrationNumber;
        this.qualification = qualification;
        this.biography = biography;
        this.consultationFee = consultationFee;
    }

    public void updateAvailability(boolean available) {
        this.available = available;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Specialisation getSpecialisation() {
        return specialisation;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public String getQualification() {
        return qualification;
    }

    public String getBiography() {
        return biography;
    }

    public BigDecimal getConsultationFee() {
        return consultationFee;
    }

    public boolean isAvailable() {
        return available;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
