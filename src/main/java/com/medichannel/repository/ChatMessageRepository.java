package com.medichannel.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.ChatMessage;

@Repository
public interface ChatMessageRepository
        extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByAppointment_IdOrderBySentAtAsc(
            UUID appointmentId
    );

    Page<ChatMessage> findByAppointment_Id(
            UUID appointmentId,
            Pageable pageable
    );
}
