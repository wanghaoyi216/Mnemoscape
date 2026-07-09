package com.mnemoscape.auth.service;

import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Bootstraps the platform's first {@code ADMIN} (and subsequent role
 * promotions) by translating an incoming {@code POST /api/v1/admin/users/{id}/role}
 * request into one of six result branches:
 * {@code promoted}, {@code already-admin}, {@code disabled},
 * {@code secret-mismatch}, {@code missing-secret}, {@code not-found}.
 *
 * <p>This service implements the algorithm specified in
 * {@code design.md §AdminBootstrapService} and satisfies the operability
 * requirements R1.4 / R1.5 / R1.6:
 *
 * <ol>
 *   <li><strong>R1.4</strong> — provides a single command (the controller
 *       wraps this service) that promotes one user to {@code role = "ADMIN"}.</li>
 *   <li><strong>R1.5</strong> — when the bootstrap secret is configured, requests
 *       carrying {@code X-Bootstrap-Secret} matching the configured value are
 *       accepted; otherwise the caller must already hold {@code ROLE_ADMIN} or
 *       the request is rejected with HTTP 403.</li>
 *   <li><strong>R1.6</strong> — when {@code mnemoscape.admin.bootstrap-secret}
 *       (env {@code ADMIN_BOOTSTRAP_SECRET}) is unset or empty, every request
 *       that does not already carry an {@code ADMIN} authority is refused with
 *       HTTP 403, and a startup-time WARN log is emitted.</li>
 * </ol>
 *
 * <h2>Constant-time secret comparison</h2>
 *
 * Secret comparison uses {@link MessageDigest#isEqual(byte[], byte[])} to
 * defeat timing-based brute-force probes. Both inputs are encoded as UTF-8
 * bytes; null inputs are normalized to a zero-length array so equality
 * remains well-defined and the comparison still runs in constant time relative
 * to the configured secret's length.
 *
 * <h2>Audit + metrics</h2>
 *
 * Every terminal branch produces:
 * <ul>
 *   <li>A structured log entry on the dedicated {@code admin-audit} logger
 *       with {@code action=role-promotion} and {@code result=<branch>}, plus
 *       the {@code targetUserId} and {@code callerAuthority} fields the design
 *       calls for. Reject branches log at {@code WARN}; the two success
 *       branches log at {@code INFO}. Raw secret values are NEVER logged.</li>
 *   <li>An increment of the {@code mnemoscape.admin.bootstrap.attempts}
 *       Micrometer counter, tagged {@code result=<branch>} where the
 *       successful-promotion branch is tagged {@code success} (the design's
 *       canonical metric vocabulary) while the response payload uses
 *       {@code promoted}.</li>
 * </ul>
 */
@Service
public class AdminBootstrapService {

    /**
     * Logger backing the application-wide event log for this class.
     * Used for the boot-time enable/disable WARN / INFO message.
     */
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    /**
     * Dedicated structured-audit logger. Logback configuration in
     * {@code logback-admin.xml} routes this name to a rolling JSON file at
     * {@code logs/admin-audit.log} with {@code additivity=false}, so audit
     * records do not pollute the main application log (R15.4).
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("admin-audit");

    /** Action tag stamped on every audit record produced by this service. */
    private static final String AUDIT_ACTION = "role-promotion";

    /** Canonical role string for an admin caller. */
    private static final String ROLE_ADMIN = "ADMIN";

    /** Result token returned in the response when an actual promotion occurred. */
    public static final String RESULT_PROMOTED = "promoted";
    /** Result token returned in the response when the user was already ADMIN. */
    public static final String RESULT_ALREADY_ADMIN = "already-admin";

    // Internal result tokens used for audit logging and metric tagging only.
    // The metric name space treats the successful-promotion branch as
    // "success" (per AdminMetrics docs) while the response uses "promoted".
    static final String METRIC_RESULT_SUCCESS = "success";
    static final String METRIC_RESULT_ALREADY_ADMIN = RESULT_ALREADY_ADMIN;
    static final String METRIC_RESULT_DISABLED = "disabled";
    static final String METRIC_RESULT_SECRET_MISMATCH = "secret-mismatch";
    static final String METRIC_RESULT_MISSING_SECRET = "missing-secret";
    static final String METRIC_RESULT_NOT_FOUND = "not-found";

    private final UserRepository userRepository;
    private final AdminMetrics adminMetrics;

    /**
     * Configured bootstrap secret. Read from
     * {@code mnemoscape.admin.bootstrap-secret} (which itself defaults to the
     * {@code ADMIN_BOOTSTRAP_SECRET} environment variable in
     * {@code application.yml}). When this is {@code null} or blank the
     * bootstrap channel is considered <em>disabled</em>, and {@link #promote}
     * rejects all non-ADMIN callers with {@code BOOTSTRAP_DISABLED}.
     */
    private final String configuredSecret;

    public AdminBootstrapService(
            UserRepository userRepository,
            AdminMetrics adminMetrics,
            @Value("${mnemoscape.admin.bootstrap-secret:}") String configuredSecret) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.adminMetrics = Objects.requireNonNull(adminMetrics, "adminMetrics");
        this.configuredSecret = configuredSecret;
    }

    /**
     * Emit a startup-time log line describing whether the bootstrap channel is
     * enabled. R1.6 mandates a WARN on unset/blank; the enabled branch logs
     * the secret <em>length</em> only — never the value — so operators can
     * confirm an env update without exposing the secret to the log stream.
     */
    @PostConstruct
    void warnIfDisabled() {
        if (isBootstrapDisabled()) {
            log.warn("ADMIN_BOOTSTRAP_SECRET is unset; role-promotion endpoint will reject all requests");
        } else {
            log.info("Bootstrap endpoint enabled (secret length={})", configuredSecret.length());
        }
    }

    /**
     * Promote the user identified by {@code targetUserId} to {@code ADMIN}.
     *
     * <p>The full algorithm follows {@code design.md §Bootstrap pseudo-code}.
     * Branch outcomes:
     * <ol>
     *   <li><strong>disabled</strong>: bootstrap secret env unset and caller is
     *       not already an ADMIN → {@code 403 BOOTSTRAP_DISABLED}.</li>
     *   <li><strong>secret-mismatch</strong>: caller supplied a secret that does
     *       not match the configured value, and the caller is not already an
     *       ADMIN → {@code 403 BOOTSTRAP_REJECTED}.</li>
     *   <li><strong>missing-secret</strong>: caller supplied no secret header
     *       and is not already an ADMIN → {@code 403 BOOTSTRAP_REJECTED}.</li>
     *   <li><strong>not-found</strong>: target user id does not exist →
     *       {@code 404 USER_NOT_FOUND}.</li>
     *   <li><strong>already-admin</strong>: target user is already ADMIN; the
     *       call is idempotent and returns {@code 200} without persisting.</li>
     *   <li><strong>promoted</strong>: target user role is changed to ADMIN
     *       and persisted via {@link UserRepository#save}.</li>
     * </ol>
     *
     * <p>The transactional boundary is intentionally narrow: the read + write
     * for the target user runs in a single transaction so the read-modify-write
     * window stays short and any concurrent promotion request observes
     * consistent state.
     *
     * @param targetUserId   id of the user to promote; must not be {@code null}
     *                       or blank.
     * @param providedSecret raw value of the {@code X-Bootstrap-Secret} header,
     *                       or {@code null} if absent. Never logged.
     * @param callerRole     resolved authority of the caller as a string;
     *                       {@code "ADMIN"} for an authenticated admin,
     *                       anything else (including {@code null}) is treated
     *                       as a non-admin caller.
     * @param callerUserId   the caller's user id (gateway-injected via
     *                       {@code X-User-Id}), or {@code null} for anonymous.
     *                       Used in the audit record for forensic traceability.
     * @return outcome record carrying the user's id, the resulting role string
     *         (always {@code "ADMIN"}), and the result token
     *         {@code "promoted"} or {@code "already-admin"}.
     * @throws BizException 400/403/404 per the branch table above.
     */
    @Transactional(rollbackFor = Exception.class)
    public RolePromotionResult promote(
            String targetUserId,
            String providedSecret,
            String callerRole,
            String callerUserId) {

        if (targetUserId == null || targetUserId.isBlank()) {
            throw BizException.badRequest("targetUserId is required");
        }

        boolean callerIsAdmin = ROLE_ADMIN.equals(callerRole);

        // 1) Global disable check (R1.6). When the env var is unset or blank,
        //    only an already-authenticated ADMIN may proceed; everyone else
        //    is refused immediately.
        if (isBootstrapDisabled() && !callerIsAdmin) {
            recordReject(METRIC_RESULT_DISABLED, targetUserId, callerRole, callerUserId);
            throw new BizException(403, "BOOTSTRAP_DISABLED");
        }

        // 2) Secret verification (R1.5). The header may either be present or
        //    absent; both branches must defend against a non-admin caller
        //    being able to drive a promotion without a valid secret.
        if (providedSecret != null) {
            if (!secretsEqual(configuredSecret, providedSecret) && !callerIsAdmin) {
                recordReject(METRIC_RESULT_SECRET_MISMATCH, targetUserId, callerRole, callerUserId);
                throw new BizException(403, "BOOTSTRAP_REJECTED");
            }
        } else {
            if (!callerIsAdmin) {
                recordReject(METRIC_RESULT_MISSING_SECRET, targetUserId, callerRole, callerUserId);
                throw new BizException(403, "BOOTSTRAP_REJECTED");
            }
        }

        // 3) Resolve target user.
        User user = userRepository.findById(targetUserId).orElse(null);
        if (user == null) {
            recordReject(METRIC_RESULT_NOT_FOUND, targetUserId, callerRole, callerUserId);
            throw new BizException(404, "USER_NOT_FOUND");
        }

        // 4) Idempotency: already ADMIN → no-op success.
        if (ROLE_ADMIN.equals(user.getRole())) {
            adminMetrics.bootstrapAttempts(METRIC_RESULT_ALREADY_ADMIN).increment();
            AUDIT.info("admin-bootstrap",
                    StructuredArguments.kv("action", AUDIT_ACTION),
                    StructuredArguments.kv("result", METRIC_RESULT_ALREADY_ADMIN),
                    StructuredArguments.kv("targetUserId", targetUserId),
                    StructuredArguments.kv("callerAuthority", normalizeRoleForAudit(callerRole)),
                    StructuredArguments.kv("callerUserId", callerUserId),
                    StructuredArguments.kv("ts", System.currentTimeMillis())
            );
            return new RolePromotionResult(user.getId(), ROLE_ADMIN, RESULT_ALREADY_ADMIN);
        }

        // 5) Promote.
        user.setRole(ROLE_ADMIN);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        adminMetrics.bootstrapAttempts(METRIC_RESULT_SUCCESS).increment();
        AUDIT.info("admin-bootstrap",
                StructuredArguments.kv("action", AUDIT_ACTION),
                StructuredArguments.kv("result", METRIC_RESULT_SUCCESS),
                StructuredArguments.kv("targetUserId", targetUserId),
                StructuredArguments.kv("callerAuthority", normalizeRoleForAudit(callerRole)),
                StructuredArguments.kv("callerUserId", callerUserId),
                StructuredArguments.kv("ts", System.currentTimeMillis())
        );
        return new RolePromotionResult(user.getId(), ROLE_ADMIN, RESULT_PROMOTED);
    }

    /** {@code true} iff the configured secret is null or blank. */
    private boolean isBootstrapDisabled() {
        return configuredSecret == null || configuredSecret.isBlank();
    }

    /**
     * Constant-time comparison of two secret strings. Both inputs are encoded
     * as UTF-8 byte arrays; null values are normalized to {@code new byte[0]}
     * so {@link MessageDigest#isEqual} always receives non-null arrays. When
     * the configured secret is empty (i.e. the bootstrap channel is disabled),
     * {@code true} can only be returned for a likewise-empty supplied value,
     * which the upstream branches never accept anyway.
     */
    private static boolean secretsEqual(String configured, String provided) {
        byte[] a = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * Common reject path: bump the metric counter, write a structured WARN
     * audit record, then leave throwing the {@link BizException} to the caller
     * so the controller can map it to the correct HTTP status.
     */
    private void recordReject(String result, String targetUserId, String callerRole, String callerUserId) {
        adminMetrics.bootstrapAttempts(result).increment();
        AUDIT.warn("admin-bootstrap",
                StructuredArguments.kv("action", AUDIT_ACTION),
                StructuredArguments.kv("result", result),
                StructuredArguments.kv("targetUserId", targetUserId),
                StructuredArguments.kv("callerAuthority", normalizeRoleForAudit(callerRole)),
                StructuredArguments.kv("callerUserId", callerUserId),
                StructuredArguments.kv("ts", System.currentTimeMillis())
        );
    }

    /**
     * Normalize the caller's role string for the audit record. Null/blank
     * resolves to {@code "ANONYMOUS"} so the field is always populated; any
     * other value is upper-cased to match the canonical
     * {@code {USER, ADMIN}} vocabulary.
     */
    private static String normalizeRoleForAudit(String callerRole) {
        if (callerRole == null || callerRole.isBlank()) {
            return "ANONYMOUS";
        }
        return callerRole.trim().toUpperCase();
    }

    /**
     * Outcome of a successful (non-throwing) call to
     * {@link #promote(String, String, String, String)}.
     *
     * <p>The {@code result} discriminator is one of
     * {@link #RESULT_PROMOTED} / {@link #RESULT_ALREADY_ADMIN}, matching the
     * payload contract documented in {@code design.md §AdminUserController}.
     * Reject branches throw {@link BizException} instead of returning a
     * record, so callers never need to inspect this value to decide on an
     * HTTP status code.
     */
    public record RolePromotionResult(String userId, String role, String result) {
    }
}
