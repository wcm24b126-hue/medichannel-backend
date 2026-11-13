package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "patients")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "first_name_encrypted", nullable = false, columnDefinition = "TEXT")
    private String firstNameEncrypted;

    @Column(name = "last_name_encrypted", nullable = false, columnDefinition = "TEXT")
    private String lastNameEncrypted;

    @Column(name = "phone_encrypted", columnDefinition = "TEXT")
    private String phoneEncrypted;

    @Column(name = "nic_lookup_hash", length = 64)
    private String nicLookupHash;

    @Column(name = "nic_encrypted", columnDefinition = "TEXT")
    private String nicEncrypted;

    @Column(name = "address_encrypted", columnDefinition = "TEXT")
    private String addressEncrypted;

    @Column(name = "date_of_birth_encrypted", columnDefinition = "TEXT")
    private String dateOfBirthEncrypted;

    @Column(name = "gender_encrypted", columnDefinition = "TEXT")
    private String genderEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Patient() {
    }

    public Patient(
            User user,
            String firstNameEncrypted,
            String lastNameEncrypted,
            String phoneEncrypted,
            String nicLookupHash,
            String dateOfBirthEncrypted,
            String genderEncrypted
    ) {
        this(
                user,
                firstNameEncrypted,
                lastNameEncrypted,
                phoneEncrypted,
                nicLookupHash,
                null,
                null,
                dateOfBirthEncrypted,
                genderEncrypted
        );
    }

    public Patient(
            User user,
            String firstNameEncrypted,
            String lastNameEncrypted,
            String phoneEncrypted,
            String nicLookupHash,
            String nicEncrypted,
            String addressEncrypted,
            String dateOfBirthEncrypted,
            String genderEncrypted
    ) {
        this.user = user;
        this.firstNameEncrypted = firstNameEncrypted;
        this.lastNameEncrypted = lastNameEncrypted;
        this.phoneEncrypted = phoneEncrypted;
        this.nicLookupHash = nicLookupHash;
        this.nicEncrypted = nicEncrypted;
        this.addressEncrypted = addressEncrypted;
        this.dateOfBirthEncrypted = dateOfBirthEncrypted;
        this.genderEncrypted = genderEncrypted;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    public void updateProfile(
            String firstNameEncrypted,
            String lastNameEncrypted,
            String phoneEncrypted,
            String nicLookupHash,
            String dateOfBirthEncrypted,
            String genderEncrypted
    ) {
        updateProfile(
                firstNameEncrypted,
                lastNameEncrypted,
                phoneEncrypted,
                nicLookupHash,
                null,
                null,
                dateOfBirthEncrypted,
                genderEncrypted
        );
    }

    public void updateProfile(
            String firstNameEncrypted,
            String lastNameEncrypted,
            String phoneEncrypted,
            String nicLookupHash,
            String nicEncrypted,
            String addressEncrypted,
            String dateOfBirthEncrypted,
            String genderEncrypted
    ) {
        this.firstNameEncrypted = firstNameEncrypted;
        this.lastNameEncrypted = lastNameEncrypted;
        this.phoneEncrypted = phoneEncrypted;
        this.nicLookupHash = nicLookupHash;
        this.nicEncrypted = nicEncrypted;
        this.addressEncrypted = addressEncrypted;
        this.dateOfBirthEncrypted = dateOfBirthEncrypted;
        this.genderEncrypted = genderEncrypted;
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

    public String getFirstNameEncrypted() {
        return firstNameEncrypted;
    }

    public String getLastNameEncrypted() {
        return lastNameEncrypted;
    }

    public String getPhoneEncrypted() {
        return phoneEncrypted;
    }

    public String getNicLookupHash() {
        return nicLookupHash;
    }

    public String getNicEncrypted() {
        return nicEncrypted;
    }

    public String getAddressEncrypted() {
        return addressEncrypted;
    }

    public String getDateOfBirthEncrypted() {
        return dateOfBirthEncrypted;
    }

    public String getGenderEncrypted() {
        return genderEncrypted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
