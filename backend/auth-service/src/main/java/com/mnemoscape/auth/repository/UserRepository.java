package com.mnemoscape.auth.repository;

import com.mnemoscape.auth.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    java.util.List<User> findByUsernameContainingIgnoreCase(String username);

    /**
     * Strict {@code (id, username)} projection used by
     * {@code POST /api/v1/users/batch-usernames}.
     *
     * <p>Intentionally written as a constructor-expression JPQL query rather
     * than {@code findAllById} + entity hydration: pulling whole {@link User}
     * rows would load {@code email} / {@code passwordHash} / {@code role} /
     * {@code verified} columns into the JVM, which is exactly the shape of
     * accidental leakage admin-dashboard Requirement 15.2 forbids. Selecting
     * the projection record directly keeps those columns off the wire and
     * out of memory entirely.
     *
     * <p>Ids that don't match any row are silently absent from the result —
     * callers are expected to treat the returned list as a sparse mapping.
     */
    @Query("""
            SELECT new com.mnemoscape.auth.repository.IdUsernameProjection(u.id, u.username)
            FROM User u
            WHERE u.id IN :userIds
            """)
    List<IdUsernameProjection> findIdUsernameByIdIn(@Param("userIds") Collection<String> userIds);
}
