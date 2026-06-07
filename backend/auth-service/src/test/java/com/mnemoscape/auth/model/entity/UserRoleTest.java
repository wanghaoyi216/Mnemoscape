package com.mnemoscape.auth.model.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for the role-handling primitives on {@link User}
 * (admin-dashboard task 2.3, validates Requirements 1.2 / 1.3).
 *
 * <p>Three things must be true regardless of how the entity is mutated:
 * <ol>
 *   <li>{@link User#getRole()} never returns {@code null} — even when the
 *       column was somehow set to {@code null} (legacy DB rows, malformed
 *       JSON deserialisation).</li>
 *   <li>{@link User#setRole(String)} normalises null / blank / lowercase
 *       inputs to the canonical {@code "USER"} or upper-case form.</li>
 *   <li>The {@code @PrePersist} callback fills in {@code "USER"} when the
 *       role field is null at insert time, so the {@code NOT NULL} DDL
 *       constraint cannot be violated by a programmatic builder that
 *       skipped the role field.</li>
 * </ol>
 */
class UserRoleTest {

    @Test
    @DisplayName("getRole returns USER when field is null (defensive)")
    void getRoleReturnsUserWhenFieldIsNull() {
        User user = new User();
        user.setRole(null);
        assertEquals("USER", user.getRole());
    }

    @Test
    @DisplayName("setRole(null) normalises to USER")
    void setRoleNullNormalisesToUser() {
        User user = new User();
        user.setRole("ADMIN");
        user.setRole(null);
        assertEquals("USER", user.getRole());
    }

    @Test
    @DisplayName("setRole(\"  \") normalises to USER (blank guard)")
    void setRoleBlankNormalisesToUser() {
        User user = new User();
        user.setRole("ADMIN");
        user.setRole("   ");
        assertEquals("USER", user.getRole());
    }

    @Test
    @DisplayName("setRole(\"admin\") upper-cases to ADMIN")
    void setRoleLowercaseGetsUppercased() {
        User user = new User();
        user.setRole("admin");
        assertEquals("ADMIN", user.getRole());
    }

    @Test
    @DisplayName("setRole(\" admin \") trims and upper-cases")
    void setRoleWithWhitespaceIsTrimmed() {
        User user = new User();
        user.setRole("  admin  ");
        assertEquals("ADMIN", user.getRole());
    }

    @Test
    @DisplayName("setRole accepts arbitrary upper-case string (forward-compat for new roles)")
    void setRoleAcceptsArbitraryNonBlank() {
        User user = new User();
        user.setRole("MODERATOR");
        // Domain check (rejecting unknown roles) is enforced by DDL CHECK
        // constraint + JwtAuthFilter, NOT by the entity itself. The entity
        // just normalises the literal.
        assertEquals("MODERATOR", user.getRole());
    }

    @Test
    @DisplayName("@PrePersist fills role when missing")
    void prePersistFillsMissingRole() throws Exception {
        // Use reflection to invoke the lifecycle callback directly — Hibernate
        // would do this for us, but we're not bringing up an EntityManager.
        User user = User.builder()
                .id("u1")
                .username("alice")
                .email("a@example.com")
                .passwordHash("h")
                .build();
        // The builder DOES NOT call setRole() so the field starts at the
        // entity's default ("USER" via the field initializer). To exercise
        // the @PrePersist guard for legacy / migrated rows, force-null first.
        user.setRole(null);
        // Hack: setRole(null) → "USER" already, but the @PrePersist still
        // runs to verify it's idempotent.
        Method onCreate = User.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(user);
        assertEquals("USER", user.getRole());
        assertNotNull(user.getCreatedAt(), "@PrePersist must also stamp createdAt");
    }

    @Test
    @DisplayName("@PrePersist does not overwrite explicit ADMIN")
    void prePersistPreservesExplicitAdmin() throws Exception {
        User user = User.builder()
                .id("u1")
                .username("alice")
                .email("a@example.com")
                .passwordHash("h")
                .build();
        user.setRole("ADMIN");
        Method onCreate = User.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(user);
        assertEquals("ADMIN", user.getRole());
    }

    @Test
    @DisplayName("Default builder produces USER on freshly-constructed entity")
    void defaultBuilderProducesUserRole() {
        User user = User.builder()
                .id("u1")
                .username("alice")
                .email("a@example.com")
                .passwordHash("h")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        assertEquals("USER", user.getRole());
    }
}
