package com.medichannel.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.Notice;

@Repository
public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    List<Notice> findByPublishedTrueOrderByPublishedAtDesc();

    List<Notice> findAllByOrderByCreatedAtDesc();
}
