package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.RoomType;

import jakarta.persistence.*;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "room_number", nullable = false, unique = true, length = 30)
    private String roomNumber;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 30)
    private RoomType roomType = RoomType.CONSULTATION;

    @Column(nullable = false)
    private int capacity = 1;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Room() {
    }

    public Room(
            String roomNumber,
            String name,
            String location,
            RoomType roomType,
            int capacity
    ) {
        this.roomNumber = roomNumber;
        this.name = name;
        this.location = location;
        this.roomType = roomType;
        this.capacity = capacity;
    }

    public void updateDetails(
            String roomNumber,
            String name,
            String location,
            RoomType roomType,
            int capacity
    ) {
        this.roomNumber = roomNumber;
        this.name = name;
        this.location = location;
        this.roomType = roomType;
        this.capacity = capacity;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
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

    public String getRoomNumber() {
        return roomNumber;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public int getCapacity() {
        return capacity;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
