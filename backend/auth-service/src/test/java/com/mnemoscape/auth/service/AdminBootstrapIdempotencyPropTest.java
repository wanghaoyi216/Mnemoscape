package com.mnemoscape.auth.service;

import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for bootstrap idempotency (admin-dashboard task 6.3 /
 * Property 11, validates Requirement 1.4).
 *
 * <h2>Property 11 — Bootstrap idempotency</h2>
 * <pre>
 *     ∀ user where role == "ADMIN" :
 *         promote(user.id, validSecret, ...)
 *             returns "already-admin" without UPDATE
 * </pre>
 *
 * <p>The property iterates over arbitrary user ids, secrets, and caller
 * authorities; whenever the target's stored role is ADMIN, the call must
 * be a no-op (no save) returning the {@code already-admin} discriminator.
 *
 * <p>This complements the branch-coverage tests in
 * {@code AdminBootstrapServiceTest} by exercising lots of randomised inputs
 * against the same invariant: "already-admin" never persists.
 */
class AdminBootstrapIdempotencyPropTest {

    private static final String CONFIGURED_SECRET = "secret-XYZ";

    /**
     * Property 11 — every promote() against an existing ADMIN MUST be a
     * no-op idempotent return, regardless of who's calling and which secret
     * they passed (provided the call would otherwise be authorised).
     */
    @Property
    void alreadyAdminIsAlwaysIdempotent(
            @ForAll("nonBlankUserIds") String userId,
            @ForAll("validCallerInputs") CallerInput input) {
        UserRepository repo = Mockito.mock(UserRepository.class);
        AdminMetrics metrics = new AdminMetrics(new SimpleMeterRegistry());
        AdminBootstrapService svc = new AdminBootstrapService(repo, metrics, CONFIGURED_SECRET);

        User existing = User.builder()
                .id(userId)
                .username("u-" + userId)
                .email(userId + "@example.com")
                .passwordHash("h")
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        existing.setRole("ADMIN");
        when(repo.findById(userId)).thenReturn(Optional.of(existing));

        var result = svc.promote(userId, input.secret(), input.callerRole(), input.callerUserId());

        assertEquals(userId, result.userId());
        assertEquals("ADMIN", result.role());
        assertEquals("already-admin", result.result());
        // Most important invariant: no DB write.
        verify(repo, never()).save(any());
    }

    /**
     * Property 11 corollary — every successful promotion of a non-admin user
     * MUST persist exactly once (save called with the entity carrying ADMIN).
     * This is the dual of the idempotency property — together they pin down
     * the full "ADMIN role state machine".
     */
    @Property
    void promotionFromUserPersistsExactlyOnce(
            @ForAll("nonBlankUserIds") String userId,
            @ForAll("validCallerInputs") CallerInput input) {
        UserRepository repo = Mockito.mock(UserRepository.class);
        AdminMetrics metrics = new AdminMetrics(new SimpleMeterRegistry());
        AdminBootstrapService svc = new AdminBootstrapService(repo, metrics, CONFIGURED_SECRET);

        User existing = User.builder()
                .id(userId)
                .username("u-" + userId)
                .email(userId + "@example.com")
                .passwordHash("h")
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        existing.setRole("USER");
        when(repo.findById(userId)).thenReturn(Optional.of(existing));

        var result = svc.promote(userId, input.secret(), input.callerRole(), input.callerUserId());

        assertEquals(userId, result.userId());
        assertEquals("ADMIN", result.role());
        assertEquals("promoted", result.result());
        verify(repo, Mockito.times(1)).save(any(User.class));
    }

    @Provide
    Arbitrary<String> nonBlankUserIds() {
        // Realistic UUID-like ids of varied length, never blank.
        return Arbitraries.strings().alpha().numeric()
                .ofMinLength(4).ofMaxLength(36);
    }

    /**
     * Generate caller inputs that would AUTHORISE the promotion, i.e. either
     * matches the secret OR the caller is already ADMIN. Inputs that would
     * be rejected (missing secret + non-admin caller) are excluded so the
     * property is well-defined: when the call IS authorised, the contract is
     * idempotent for ADMINs and saves for USERs.
     */
    @Provide
    Arbitrary<CallerInput> validCallerInputs() {
        // Two flavours of authorised input:
        //   1) anonymous / non-admin caller with the correct secret
        //   2) authenticated admin caller (any secret value)
        Arbitrary<CallerInput> withSecret = Arbitraries.of(CONFIGURED_SECRET)
                .flatMap(s -> Arbitraries.strings().ofMaxLength(8).map(callerId ->
                        new CallerInput(s, "USER", callerId)));
        Arbitrary<CallerInput> adminCaller = Arbitraries.of("ADMIN")
                .flatMap(role -> Arbitraries.strings().ofMaxLength(16).map(callerId ->
                        new CallerInput(null, role, "admin-" + callerId)));
        Arbitrary<CallerInput> adminCallerWithSecret = Arbitraries.of("ADMIN")
                .flatMap(role -> Arbitraries.strings().ofMaxLength(8).map(s ->
                        new CallerInput(s, role, "admin-self")));
        return Arbitraries.oneOf(withSecret, adminCaller, adminCallerWithSecret);
    }

    record CallerInput(String secret, String callerRole, String callerUserId) {}
}
