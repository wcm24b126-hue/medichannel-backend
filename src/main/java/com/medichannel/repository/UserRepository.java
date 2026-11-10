package com.medichannel.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.medichannel.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT u
            FROM User u
            JOIN FETCH u.role
            WHERE u.email = :email
            """)
    Optional<User> findByEmailForAuthentication(
            @Param("email") String email
    );

    @Query("""
            SELECT u
            FROM User u
            JOIN FETCH u.role
            WHERE u.id = :userId
            """)
    Optional<User> findByIdWithRole(
            @Param("userId") UUID userId
    );
}
