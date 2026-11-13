package com.medichannel.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.room.RoomRequest;
import com.medichannel.dto.room.RoomResponse;
import com.medichannel.entity.Room;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.RoomRepository;

@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final AuditLogService auditLogService;

    public RoomService(
            RoomRepository roomRepository,
            AuditLogService auditLogService
    ) {
        this.roomRepository = roomRepository;
        this.auditLogService = auditLogService;
    }

    private String getActorEmail() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                if (auth.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                    String email = jwt.getClaimAsString("email");
                    if (email != null && !email.isBlank()) {
                        return email;
                    }
                }
                if (auth.getName() != null && !auth.getName().isBlank() && auth.getName().contains("@")) {
                    return auth.getName();
                }
            }
        } catch (Exception ignored) {
        }
        return "admin@medichannel.demo";
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getAllRooms() {
        return roomRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoom(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional
    public RoomResponse create(RoomRequest request) {

        String roomNumber = normalizeRoomNumber(request.roomNumber());

        if (roomRepository.existsByRoomNumber(roomNumber)) {
            throw new ConflictException(
                    "A room with this room number already exists."
            );
        }

        Room room = new Room(
                roomNumber,
                normalizeName(request.name()),
                normalizeLocation(request.location()),
                request.roomType(),
                request.capacity()
        );

        Room saved = roomRepository.saveAndFlush(room);
        auditLogService.log(
                getActorEmail(),
                "ADMIN",
                "Room created",
                "Created room " + saved.getRoomNumber() + " (" + saved.getName() + ")",
                "10.0.1.5"
        );
        return toResponse(saved);
    }

    @Transactional
    public RoomResponse update(UUID id, RoomRequest request) {

        Room room = findById(id);
        String roomNumber = normalizeRoomNumber(request.roomNumber());

        roomRepository.findByRoomNumber(roomNumber)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "A room with this room number already exists."
                    );
                });

        room.updateDetails(
                roomNumber,
                normalizeName(request.name()),
                normalizeLocation(request.location()),
                request.roomType(),
                request.capacity()
        );

        return toResponse(room);
    }

    @Transactional
    public RoomResponse deactivate(UUID id) {
        Room room = findById(id);
        room.deactivate();
        return toResponse(room);
    }

    @Transactional
    public RoomResponse activate(UUID id) {
        Room room = findById(id);
        room.activate();
        return toResponse(room);
    }

    private Room findById(UUID id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Room not found."
                ));
    }

    private String normalizeRoomNumber(String roomNumber) {
        if (roomNumber == null || roomNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "Room number is required."
            );
        }

        return roomNumber.trim().toUpperCase();
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Room name is required."
            );
        }

        return name.trim().replaceAll("\\s+", " ");
    }

    private String normalizeLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }

        return location.trim();
    }

    private RoomResponse toResponse(Room room) {
        return new RoomResponse(
                room.getId(),
                room.getRoomNumber(),
                room.getName(),
                room.getLocation(),
                room.getRoomType(),
                room.getCapacity(),
                room.isActive(),
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }
}
