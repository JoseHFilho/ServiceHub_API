package com.servicehub.repository;

import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import com.servicehub.model.dto.ProviderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Collection;
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
    @Query("select new com.servicehub.model.dto.ProviderResponse(u.id, u.fullName, u.avatarUrl) from User u where u.active = true and u.role = :role")
    Page<ProviderResponse> findPublicProviders(@Param("role") UserRole role, Pageable page);

    @Query("select new com.servicehub.model.dto.ProviderResponse(u.id, u.fullName, u.avatarUrl) from User u where u.active = true and u.role = :role and u.id in :ids")
    List<ProviderResponse> findPublicProvidersByIds(@Param("ids") Collection<Long> ids, @Param("role") UserRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findLockedById(@Param("id") Long id);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u SET u.passwordHash = :newHash, u.updatedAt = :updatedAt, u.credentialVersion = u.credentialVersion + 1
            WHERE u.id = :id AND u.passwordHash = :oldHash AND u.active = true AND u.credentialVersion = :credentialVersion
            """)
    int upgradePassword(@Param("id") Long id, @Param("oldHash") String oldHash,
            @Param("newHash") String newHash, @Param("updatedAt") LocalDateTime updatedAt, @Param("credentialVersion") long credentialVersion);
    List<User> findAllByActiveTrue();
    List<User> findByFullNameContainingIgnoreCase(String name);

    @Query("SELECT u FROM User u WHERE u.createdAt >= :date")
    List<User> findRecentUsers(@Param("date") LocalDateTime date);

    @Query(value = "SELECT * FROM users WHERE is_active = true LIMIT :limit", nativeQuery = true)
    List<User> findActiveUsers(@Param("limit") int limit);
}
