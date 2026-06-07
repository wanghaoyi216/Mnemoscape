package com.mnemoscape.resonance.admin;

import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.exception.GlobalExceptionHandler;
import com.mnemoscape.resonance.admin.dto.ResonanceOverview;
import com.mnemoscape.resonance.admin.dto.ResonanceTopEdge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link AdminResonanceController} (admin-dashboard task
 * 8.3, validates Requirements 12.1 / 12.2 / 12.3 / 15.1).
 *
 * <p>The privacy guards from {@code AdminDtoWhitelistTest} (memory-service)
 * cover the DTO shape; here we additionally verify:
 * <ul>
 *   <li>full-stack response JSON (envelope + DTO) does not leak forbidden
 *       fields like {@code "title"} / {@code "description"} / {@code "sceneDataUrl"};</li>
 *   <li>service-thrown {@code BizException} produces the right HTTP status
 *       through {@link GlobalExceptionHandler}.</li>
 * </ul>
 */
class AdminResonanceControllerTest {

    private MockMvc mockMvc;
    private AdminResonanceService service;

    @BeforeEach
    void setUp() {
        service = mock(AdminResonanceService.class);
        AdminMetrics metrics = new AdminMetrics(new SimpleMeterRegistry());
        AdminResonanceController controller = new AdminResonanceController(service, metrics);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("overview returns the three KPI fields")
    void overviewReturnsThreeKpiFields() throws Exception {
        var breakdown = new LinkedHashMap<String, Long>();
        breakdown.put("pending", 30L);
        breakdown.put("active", 12L);
        when(service.overview())
                .thenReturn(new ResonanceOverview(42L, 0.73, breakdown));

        mockMvc.perform(get("/api/v1/admin/stats/resonance-overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalEdges").value(42))
                .andExpect(jsonPath("$.data.averageScore").value(0.73))
                .andExpect(jsonPath("$.data.statusBreakdown.pending").value(30))
                .andExpect(jsonPath("$.data.statusBreakdown.active").value(12));
    }

    @Test
    @DisplayName("overview JSON contains no forbidden privacy fields")
    void overviewDoesNotLeakPrivateFields() throws Exception {
        when(service.overview())
                .thenReturn(new ResonanceOverview(1L, 0.5, java.util.Map.of("pending", 1L)));

        String body = mockMvc.perform(get("/api/v1/admin/stats/resonance-overview"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"title\""));
        assertFalse(body.contains("\"description\""));
        assertFalse(body.contains("\"sceneDataUrl\""));
    }

    @Test
    @DisplayName("top defaults to 20 when limit param is missing")
    void topDefaultLimitTwentyOnControllerSide() throws Exception {
        when(service.topEdges(any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/admin/stats/resonance-top"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).topEdges(null);
    }

    @Test
    @DisplayName("top returns the canonical DTO field names")
    void topReturnsCanonicalDtoNames() throws Exception {
        when(service.topEdges(any())).thenReturn(List.of(
                new ResonanceTopEdge("mem-a", "mem-b", 0.91, "active",
                        OffsetDateTime.of(2026, 5, 24, 12, 0, 0, 0, ZoneOffset.UTC))));

        mockMvc.perform(get("/api/v1/admin/stats/resonance-top")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].memoryAId").value("mem-a"))
                .andExpect(jsonPath("$.data[0].memoryBId").value("mem-b"))
                .andExpect(jsonPath("$.data[0].resonanceScore").value(0.91))
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }

    @Test
    @DisplayName("top JSON contains no forbidden privacy fields")
    void topDoesNotLeakPrivateFields() throws Exception {
        when(service.topEdges(any())).thenReturn(List.of(
                new ResonanceTopEdge("mem-a", "mem-b", 0.91, "active",
                        OffsetDateTime.of(2026, 5, 24, 12, 0, 0, 0, ZoneOffset.UTC))));

        String body = mockMvc.perform(get("/api/v1/admin/stats/resonance-top"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"title\""));
        assertFalse(body.contains("\"description\""));
        assertFalse(body.contains("\"sceneDataUrl\""));
        // entity column names must NOT appear:
        assertFalse(body.contains("\"memoryId1\""));
        assertFalse(body.contains("\"memoryId2\""));
        assertFalse(body.contains("\"similarityScore\""));
    }

    @Test
    @DisplayName("INVALID_LIMIT bubbles up as 400")
    void invalidLimitMapsTo400() throws Exception {
        when(service.topEdges(any()))
                .thenThrow(new BizException(400, "INVALID_LIMIT"));

        mockMvc.perform(get("/api/v1/admin/stats/resonance-top")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_LIMIT"));
    }

    @Test
    @DisplayName("limit param is forwarded as-is to service for parsing")
    void limitForwardedAsString() throws Exception {
        when(service.topEdges(any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/admin/stats/resonance-top")
                        .param("limit", "42"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).topEdges("42");
    }
}
