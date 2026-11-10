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

    public RoomService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
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

        return toResponse(roomRepository.saveAndFlush(room));
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
