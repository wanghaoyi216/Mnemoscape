package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.dto.RolePromotionRequest;
import com.mnemoscape.auth.service.AdminBootstrapService;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link AdminUserController#promoteRole} (admin-dashboard
 * task 6.4 control surface — body validation, error mapping).
 *
 * <p>Uses standalone setup (no Spring boot context, no security filter
 * chain) so the test focuses on the controller's own contract:
 * <ul>
 *   <li>body {@code role != "ADMIN"} → 400 INVALID_ROLE returned by the
 *       controller (without consulting the bootstrap service);</li>
 *   <li>service-thrown BizExceptions surface as the right HTTP status via
 *       {@link GlobalExceptionHandler};</li>
 *   <li>the {@code X-User-Role} / {@code X-User-Id} headers are passed through
 *       as caller identity to the service (verifying the gateway-injection
 *       contract is wired correctly).</li>
 * </ul>
 */
class AdminUserControllerTest {

    private MockMvc mockMvc;
    private AdminBootstrapService bootstrapService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        bootstrapService = mock(AdminBootstrapService.class);
        AdminUserController controller = new AdminUserController(bootstrapService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("body role=ADMIN reaches service with header-derived caller identity")
    void adminBodyReachesService() throws Exception {
        when(bootstrapService.promote(eq("u1"), any(), eq("ADMIN"), eq("caller-X")))
                .thenReturn(new AdminBootstrapService.RolePromotionResult(
                        "u1", "ADMIN", "promoted"));
        String body = mapper.writeValueAsString(new RolePromotionRequest("ADMIN"));

        mockMvc.perform(post("/api/v1/admin/users/u1/role")
                        .header("X-User-Role", "ADMIN")
                        .header("X-User-Id", "caller-X")
                        .header("X-Bootstrap-Secret", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("u1"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.result").value("promoted"));

        verify(bootstrapService).promote("u1", "", "ADMIN", "caller-X");
    }

    @Test
    @DisplayName("body role=USER returns 400 INVALID_ROLE without invoking service")
    void rejectNonAdminRoleBody() throws Exception {
        String body = mapper.writeValueAsString(new RolePromotionRequest("USER"));
        mockMvc.perform(post("/api/v1/admin/users/u1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("INVALID_ROLE"));
        verify(bootstrapService, never()).promote(any(), any(), any(), any());
    }

    @Test
    @DisplayName("body with null role returns 400 INVALID_ROLE")
    void rejectNullRole() throws Exception {
        // Send raw JSON with role=null
        mockMvc.perform(post("/api/v1/admin/users/u1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_ROLE"));
        verify(bootstrapService, never()).promote(any(), any(), any(), any());
    }

    @Test
    @DisplayName("body role=ADMIN with surrounding whitespace is accepted (trimmed)")
    void acceptsTrimmedAdmin() throws Exception {
        when(bootstrapService.promote(any(), any(), any(), any()))
                .thenReturn(new AdminBootstrapService.RolePromotionResult(
                        "u1", "ADMIN", "promoted"));
        mockMvc.perform(post("/api/v1/admin/users/u1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"  ADMIN  \"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("body role=admin (lowercase) returns 400 INVALID_ROLE")
    void rejectLowercaseRoleBody() throws Exception {
        String body = mapper.writeValueAsString(new RolePromotionRequest("admin"));
        mockMvc.perform(post("/api/v1/admin/users/u1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_ROLE"));
    }

    @Test
    @DisplayName("BOOTSTRAP_DISABLED from service surfaces as 403")
    void bootstrapDisabledMapsTo403() throws Exception {
        when(bootstrapService.promote(any(), any(), any(), any()))
                .thenThrow(new BizException(403, "BOOTSTRAP_DISABLED"));
        String body = mapper.writeValueAsString(new RolePromotionRequest("ADMIN"));
        mockMvc.perform(post("/api/v1/admin/users/u1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("BOOTSTRAP_DISABLED"));
    }

    @Test
    @DisplayName("BOOTSTRAP_REJECTED from service surfaces as 403")
    void bootstrapRejectedMapsTo403() throws Exception {
        when(bootstrapService.promote(any(), any(), any(), any()))
                .thenThrow(new BizException(403, "BOOTSTRAP_REJECTED"));
        String body = mapper.writeValueAsString(new RolePromotionRequest("ADMIN"));
        mockMvc.perform(post("/api/v1/admin/users/u1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("BOOTSTRAP_REJECTED"));
    }

    @Test
    @DisplayName("USER_NOT_FOUND from service surfaces as 404")
    void userNotFoundMapsTo404() throws Exception {
        when(bootstrapService.promote(any(), any(), any(), any()))
                .thenThrow(new BizException(404, "USER_NOT_FOUND"));
        String body = mapper.writeValueAsString(new RolePromotionRequest("ADMIN"));
        mockMvc.perform(post("/api/v1/admin/users/missing/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("USER_NOT_FOUND"));
    }
}
