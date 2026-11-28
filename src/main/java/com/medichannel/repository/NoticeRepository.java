package com.medichannel.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.Notice;

@Repository
public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    List<Notice> findByPublishedTrueOrderByPublishedAtDesc();

    List<Notice> findAllByOrderByCreatedAtDesc();

    @Query(
        value = """
            SELECT n FROM Notice n
            WHERE (:published IS NULL OR n.published = :published)
            ORDER BY n.createdAt DESC, n.id DESC
            """,
        countQuery = """
            SELECT COUNT(n) FROM Notice n
            WHERE (:published IS NULL OR n.published = :published)
            """
    )
    Page<Notice> findForManagement(
            @Param("published") Boolean published,
            Pageable pageable
    );
}
