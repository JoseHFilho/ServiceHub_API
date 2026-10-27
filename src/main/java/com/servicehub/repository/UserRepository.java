package com.servicehub.repository;

import com.servicehub.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findLockedById(@Param("id") Long id);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u SET u.passwordHash = :newHash, u.updatedAt = :updatedAt
            WHERE u.id = :id AND u.passwordHash = :oldHash AND u.active = true
            """)
    int upgradePassword(@Param("id") Long id, @Param("oldHash") String oldHash,
            @Param("newHash") String newHash, @Param("updatedAt") LocalDateTime updatedAt);
    List<User> findAllByActiveTrue();
    List<User> findByFullNameContainingIgnoreCase(String name);

    @Query("SELECT u FROM User u WHERE u.createdAt >= :date")
    List<User> findRecentUsers(@Param("date") LocalDateTime date);

    @Query(value = "SELECT * FROM users WHERE is_active = true LIMIT :limit", nativeQuery = true)
    List<User> findActiveUsers(@Param("limit") int limit);
}
