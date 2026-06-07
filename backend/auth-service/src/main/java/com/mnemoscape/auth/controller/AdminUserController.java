package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.dto.RolePromotionRequest;
import com.mnemoscape.auth.model.dto.RolePromotionResponse;
import com.mnemoscape.auth.service.AdminBootstrapService;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin user management endpoints under {@code /api/v1/admin/users/**}.
 *
 * <p>Today this controller exposes a single operation:
 * {@code POST /api/v1/admin/users/{userId}/role}, the role-promotion /
 * bootstrap endpoint defined by admin-dashboard task 6.4
 * (Requirements 1.4 / 1.5 / 1.6).
 *
 * <h2>Authorization model</h2>
 *
 * Two access paths converge here:
 * <ul>
 *   <li><b>Bootstrap path</b> — the request carries
 *       {@code X-Bootstrap-Secret: <env value>} but no {@code Authorization}
 *       header. The shared {@link com.mnemoscape.common.security.JwtAuthFilter}
 *       skips JWT validation when this combination is detected
 *       (see its {@code shouldNotFilter}); the security chain's
 *       {@code adminOrBootstrapSecret()} authorization manager waves the
 *       request through; the service layer performs constant-time secret
 *       comparison and returns {@code BOOTSTRAP_DISABLED} or
 *       {@code BOOTSTRAP_REJECTED} on failure.</li>
 *   <li><b>Authenticated admin path</b> — once the platform has at least one
 *       ADMIN, future promotions are driven by an existing admin via
 *       {@code Authorization: Bearer <jwt>}. JwtAuthFilter populates
 *       {@link org.springframework.security.core.context.SecurityContextHolder}
 *       with {@code ROLE_ADMIN}, the security chain enforces
 *       {@code hasRole('ADMIN')} on the path, and the service layer accepts
 *       any header combination.</li>
 * </ul>
 *
 * <h2>Body validation</h2>
 *
 * The body must contain exactly one field, {@code role}, with the literal
 * value {@code "ADMIN"}. Anything else (null, empty, {@code "USER"},
 * arbitrary strings) is rejected up-front with HTTP 400
 * {@code INVALID_ROLE} <em>before</em> any audit log is written or
 * any downstream branch is evaluated, so the audit trail does not record
 * trivially-malformed requests as bootstrap attempts.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    /** Canonical role string accepted by this endpoint. */
    private static final String ROLE_ADMIN = "ADMIN";

    /** Header name carrying the bootstrap secret on the unauthenticated path. */
    private static final String BOOTSTRAP_SECRET_HEADER = "X-Bootstrap-Secret";

    /** Header injected by the gateway after JWT validation: caller's resolved role. */
    private static final String USER_ROLE_HEADER = "X-User-Role";

    /** Header injected by the gateway after JWT validation: caller's user id. */
    private static final String USER_ID_HEADER = "X-User-Id";

    private final AdminBootstrapService adminBootstrapService;

    public AdminUserController(AdminBootstrapService adminBootstrapService) {
        this.adminBootstrapService = adminBootstrapService;
    }

    /**
     * Promote {@code userId} to {@code role = "ADMIN"}.
     *
     * <p>See class-level Javadoc for the authorization model and audit
     * semantics. The handler delegates the full algorithm — including
     * constant-time secret comparison, idempotency, and audit / metric
     * emission — to {@link AdminBootstrapService#promote(String, String, String, String)}.
     *
     * @param userId  path-bound id of the user to promote; must be non-blank
     * @param body    request body; {@code body.role()} must equal {@code "ADMIN"}
     * @param secret  optional {@code X-Bootstrap-Secret} header; passed through
     *                to the service layer for constant-time comparison
     * @param req     the underlying servlet request, used to read the
     *                gateway-injected {@code X-User-Role} / {@code X-User-Id}
     *                identity headers (both are {@code null} on the bootstrap
     *                path because no JWT was validated)
     * @return 200 {@code ApiResponse} carrying a {@link RolePromotionResponse}
     * @throws BizException on any reject branch — mapped to 400/403/404 by
     *                      {@code GlobalExceptionHandler}
     */
    @PostMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<RolePromotionResponse>> promoteRole(
            @PathVariable("userId") String userId,
            @RequestBody RolePromotionRequest body,
            @RequestHeader(value = BOOTSTRAP_SECRET_HEADER, required = false) String secret,
            HttpServletRequest req) {

        if (body == null || body.role() == null
                || !ROLE_ADMIN.equals(body.role().trim())) {
            // R1.4 — only "ADMIN" is accepted. Reject before consulting the
            // bootstrap pipeline so that malformed bodies don't pollute the
            // audit / metric stream with phantom promotion attempts.
            throw new BizException(400, "INVALID_ROLE");
        }

        String callerRole = req.getHeader(USER_ROLE_HEADER);
        String callerUserId = req.getHeader(USER_ID_HEADER);

        AdminBootstrapService.RolePromotionResult result =
                adminBootstrapService.promote(userId, secret, callerRole, callerUserId);

        return ResponseEntity.ok(ApiResponse.success(
                new RolePromotionResponse(result.userId(), result.role(), result.result())));
    }
}
