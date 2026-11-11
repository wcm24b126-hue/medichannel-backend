package com.medichannel.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.medichannel.dto.room.RoomRequest;
import com.medichannel.dto.room.RoomResponse;
import com.medichannel.service.RoomService;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    public ResponseEntity<List<RoomResponse>> getRooms() {
        return ResponseEntity.ok(roomService.getAllRooms());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomResponse> getRoom(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(roomService.getRoom(id));
    }

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            @Valid @RequestBody RoomRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roomService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoomResponse> updateRoom(
            @PathVariable UUID id,
            @Valid @RequestBody RoomRequest request
    ) {
        return ResponseEntity.ok(
                roomService.update(id, request)
        );
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<RoomResponse> deactivateRoom(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                roomService.deactivate(id)
        );
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<RoomResponse> activateRoom(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                roomService.activate(id)
        );
    }
}
