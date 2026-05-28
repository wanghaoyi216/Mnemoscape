package com.mnemoscape.auth.service;

import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.exception.BizException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for {@link AdminBootstrapService} (admin-dashboard
 * task 6.2 — covers all six branches; validates Requirements 1.4 / 1.5 / 1.6).
 *
 * <p>The six terminal branches enumerated in {@code AdminBootstrapService}:
 * <ul>
 *   <li>{@code disabled} — env unset and caller is non-ADMIN → 403 BOOTSTRAP_DISABLED</li>
 *   <li>{@code secret-mismatch} — caller supplied wrong secret and is non-ADMIN → 403 BOOTSTRAP_REJECTED</li>
 *   <li>{@code missing-secret} — no secret header and caller is non-ADMIN → 403 BOOTSTRAP_REJECTED</li>
 *   <li>{@code not-found} — target user id doesn't exist → 404 USER_NOT_FOUND</li>
 *   <li>{@code already-admin} — target is already ADMIN → idempotent OK, no save</li>
 *   <li>{@code promoted} — target was USER, becomes ADMIN, persisted</li>
 * </ul>
 */
class AdminBootstrapServiceTest {

    private static final String CONFIGURED_SECRET = "super-secret-bootstrap-value-XYZ";

    private UserRepository repo;
    private AdminMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new AdminMetrics(registry);
    }

    private AdminBootstrapService serviceWithEnabledSecret() {
        return new AdminBootstrapService(repo, metrics, CONFIGURED_SECRET);
    }

    private AdminBootstrapService serviceWithDisabledSecret() {
        return new AdminBootstrapService(repo, metrics, "");
    }

    private static long bootstrapAttemptCount(SimpleMeterRegistry reg, String result) {
        Counter c = reg.find("mnemoscape.admin.bootstrap.attempts")
                .tag("result", result)
                .counter();
        return c == null ? 0 : (long) c.count();
    }

    // ---------- Branch 1: disabled ----------

    @Nested
    @DisplayName("Branch: disabled (env unset)")
    class DisabledBranch {

        @Test
        void nonAdminCallerWithoutSecretIsRejected() {
            AdminBootstrapService svc = serviceWithDisabledSecret();
            BizException ex = assertThrows(BizException.class,
                    () -> svc.promote("u1", null, "USER", "caller-1"));
            assertEquals(403, ex.getCode());
            assertEquals("BOOTSTRAP_DISABLED", ex.getMessage());
            verify(repo, never()).findById(any());
            assertEquals(1L, bootstrapAttemptCount(registry, "disabled"));
        }

        @Test
        void nonAdminCallerWithSecretIsRejectedWhenEnvUnset() {
            // Even if the caller supplies *some* secret, when the env is unset
            // the channel is fully closed (R1.6).
            AdminBootstrapService svc = serviceWithDisabledSecret();
            BizException ex = assertThrows(BizException.class,
                    () -> svc.promote("u1", "any-value", "USER", "caller-1"));
            assertEquals(403, ex.getCode());
            assertEquals("BOOTSTRAP_DISABLED", ex.getMessage());
        }

        @Test
        void existingAdminBypassesDisabledChannel() {
            AdminBootstrapService svc = serviceWithDisabledSecret();
            User target = userWithRole("u1", "USER");
            when(repo.findById("u1")).thenReturn(Optional.of(target));

            var result = svc.promote("u1", null, "ADMIN", "caller-admin");

            assertEquals("promoted", result.result());
            assertEquals("ADMIN", result.role());
            // Save called: target was USER and got upgraded.
            verify(repo, times(1)).save(any(User.class));
        }
    }

    // ---------- Branch 2: secret-mismatch ----------

    @Nested
    @DisplayName("Branch: secret-mismatch")
    class SecretMismatchBranch {

        @Test
        void nonAdminCallerWithWrongSecretIsRejected() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            BizException ex = assertThrows(BizException.class,
                    () -> svc.promote("u1", "wrong-secret", "USER", "caller-1"));
            assertEquals(403, ex.getCode());
            assertEquals("BOOTSTRAP_REJECTED", ex.getMessage());
            verify(repo, never()).findById(any());
            assertEquals(1L, bootstrapAttemptCount(registry, "secret-mismatch"));
        }

        @Test
        void existingAdminWithWrongSecretIsStillAccepted() {
            // Once a real ADMIN exists, secret correctness is irrelevant — the
            // caller's authority alone authorises the operation.
            AdminBootstrapService svc = serviceWithEnabledSecret();
            User target = userWithRole("u1", "USER");
            when(repo.findById("u1")).thenReturn(Optional.of(target));

            var result = svc.promote("u1", "garbage", "ADMIN", "caller-admin");

            assertEquals("promoted", result.result());
        }
    }

    // ---------- Branch 3: missing-secret ----------

    @Nested
    @DisplayName("Branch: missing-secret")
    class MissingSecretBranch {

        @Test
        void nonAdminCallerWithNullSecretIsRejected() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            BizException ex = assertThrows(BizException.class,
                    () -> svc.promote("u1", null, "USER", "caller-1"));
            assertEquals(403, ex.getCode());
            assertEquals("BOOTSTRAP_REJECTED", ex.getMessage());
            verify(repo, never()).findById(any());
            assertEquals(1L, bootstrapAttemptCount(registry, "missing-secret"));
        }

        @Test
        void anonymousCallerWithNullSecretIsRejected() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            BizException ex = assertThrows(BizException.class,
                    () -> svc.promote("u1", null, null, null));
            assertEquals(403, ex.getCode());
            assertEquals("BOOTSTRAP_REJECTED", ex.getMessage());
        }
    }

    // ---------- Branch 4: not-found ----------

    @Nested
    @DisplayName("Branch: not-found")
    class NotFoundBranch {

        @Test
        void unknownUserIdYieldsNotFound() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            when(repo.findById("nope")).thenReturn(Optional.empty());

            BizException ex = assertThrows(BizException.class,
                    () -> svc.promote("nope", CONFIGURED_SECRET, "USER", "caller-1"));
            assertEquals(404, ex.getCode());
            assertEquals("USER_NOT_FOUND", ex.getMessage());
            assertEquals(1L, bootstrapAttemptCount(registry, "not-found"));
        }
    }

    // ---------- Branch 5: already-admin ----------

    @Nested
    @DisplayName("Branch: already-admin (idempotent)")
    class AlreadyAdminBranch {

        @Test
        void alreadyAdminReturnsIdempotentResultWithoutSave() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            User target = userWithRole("u1", "ADMIN");
            when(repo.findById("u1")).thenReturn(Optional.of(target));

            var result = svc.promote("u1", CONFIGURED_SECRET, "USER", "caller-1");

            assertEquals("u1", result.userId());
            assertEquals("ADMIN", result.role());
            assertEquals("already-admin", result.result());
            verify(repo, never()).save(any());
            assertEquals(1L, bootstrapAttemptCount(registry, "already-admin"));
        }

        @Test
        void alreadyAdminWorksForAdminCallerToo() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            User target = userWithRole("u1", "ADMIN");
            when(repo.findById("u1")).thenReturn(Optional.of(target));

            var result = svc.promote("u1", null, "ADMIN", "caller-admin");

            assertEquals("already-admin", result.result());
            verify(repo, never()).save(any());
        }
    }

    // ---------- Branch 6: promoted ----------

    @Nested
    @DisplayName("Branch: promoted (success path)")
    class PromotedBranch {

        @Test
        void userBecomesAdminWhenSecretMatches() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            User target = userWithRole("u1", "USER");
            when(repo.findById("u1")).thenReturn(Optional.of(target));

            var result = svc.promote("u1", CONFIGURED_SECRET, "USER", "caller-1");

            assertEquals("u1", result.userId());
            assertEquals("ADMIN", result.role());
            assertEquals("promoted", result.result());

            // Save was called with the entity now bearing ADMIN.
            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(repo, times(1)).save(cap.capture());
            assertEquals("ADMIN", cap.getValue().getRole(),
                    "saved entity must have role=ADMIN");
            assertEquals(1L, bootstrapAttemptCount(registry, "success"));
        }

        @Test
        void updatedAtIsRefreshedOnPromotion() {
            AdminBootstrapService svc = serviceWithEnabledSecret();
            User target = userWithRole("u1", "USER");
            target.setUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
            when(repo.findById("u1")).thenReturn(Optional.of(target));

            svc.promote("u1", CONFIGURED_SECRET, "USER", "caller-1");

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(repo, times(1)).save(cap.capture());
            // The original 2020-01-01 timestamp must have been overwritten with a recent one.
            assertEquals(true,
                    cap.getValue().getUpdatedAt().isAfter(LocalDateTime.of(2024, 1, 1, 0, 0)),
                    "updatedAt must be refreshed on promotion");
        }
    }

    // ---------- Input validation ----------

    @Test
    @DisplayName("blank targetUserId is rejected up-front")
    void blankTargetUserIdRejected() {
        AdminBootstrapService svc = serviceWithEnabledSecret();
        BizException ex = assertThrows(BizException.class,
                () -> svc.promote("   ", CONFIGURED_SECRET, "USER", "caller-1"));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("null targetUserId is rejected up-front")
    void nullTargetUserIdRejected() {
        AdminBootstrapService svc = serviceWithEnabledSecret();
        assertThrows(BizException.class,
                () -> svc.promote(null, CONFIGURED_SECRET, "USER", "caller-1"));
    }

    // ---------- Helpers ----------

    private static User userWithRole(String id, String role) {
        User u = User.builder()
                .id(id)
                .username("u-" + id)
                .email(id + "@example.com")
                .passwordHash("h")
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        u.setRole(role);
        return u;
    }
}
