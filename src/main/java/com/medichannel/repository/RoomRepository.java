package com.medichannel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.medichannel.entity.Room;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByRoomNumber(String roomNumber);

    boolean existsByRoomNumber(String roomNumber);

    List<Room> findByActiveTrue();
}
