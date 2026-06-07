package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.dto.ActiveUserBucket;
import com.mnemoscape.auth.service.AdminStatsService;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.exception.GlobalExceptionHandler;
import com.mnemoscape.common.exception.UpstreamUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link AdminStatsController#activeUsers} (admin-dashboard
 * task 6.7, validates Requirements 6.2–6.7).
 *
 * <p>Coverage:
 * <ul>
 *   <li>missing / invalid {@code dimension} → 400 INVALID_DIMENSION;</li>
 *   <li>{@code from > to} or unparsable date → 400 INVALID_RANGE;</li>
 *   <li>memory-service Feign failure (UpstreamUnavailableException) → 502;</li>
 *   <li>happy path passes the bucket list through.</li>
 * </ul>
 */
class AdminStatsControllerTest {

    private MockMvc mockMvc;
    private AdminStatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = mock(AdminStatsService.class);
        AdminMetrics metrics = new AdminMetrics(new SimpleMeterRegistry());
        AdminStatsController controller = new AdminStatsController(statsService, metrics);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("missing dimension returns 400 INVALID_DIMENSION")
    void missingDimensionRejected() throws Exception {
        when(statsService.aggregateActiveUsers(any(), any(), any()))
                .thenThrow(new BizException(400, "INVALID_DIMENSION"));

        mockMvc.perform(get("/api/v1/admin/stats/active-users"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_DIMENSION"));
    }

    @Test
    @DisplayName("invalid dimension token returns 400 INVALID_DIMENSION")
    void invalidDimensionRejected() throws Exception {
        when(statsService.aggregateActiveUsers(any(), any(), any()))
                .thenThrow(new BizException(400, "INVALID_DIMENSION"));

        mockMvc.perform(get("/api/v1/admin/stats/active-users")
                        .param("dimension", "HOURLY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_DIMENSION"));
    }

    @Test
    @DisplayName("from > to returns 400 INVALID_RANGE")
    void invalidRangeRejected() throws Exception {
        when(statsService.aggregateActiveUsers(any(), any(), any()))
                .thenThrow(new BizException(400, "INVALID_RANGE"));

        mockMvc.perform(get("/api/v1/admin/stats/active-users")
                        .param("dimension", "DAILY")
                        .param("from", "2026-05-25")
                        .param("to", "2026-05-22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_RANGE"));
    }

    @Test
    @DisplayName("Feign failure (UpstreamUnavailableException) returns 502 UPSTREAM_UNAVAILABLE")
    void feignFailureReturns502() throws Exception {
        when(statsService.aggregateActiveUsers(any(), any(), any()))
                .thenThrow(new UpstreamUnavailableException("memory-service"));

        mockMvc.perform(get("/api/v1/admin/stats/active-users")
                        .param("dimension", "DAILY"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    @DisplayName("happy path returns 200 + bucket list")
    void happyPathReturnsBuckets() throws Exception {
        List<ActiveUserBucket> buckets = List.of(
                new ActiveUserBucket("2026-05-22", 5L),
                new ActiveUserBucket("2026-05-23", 8L),
                new ActiveUserBucket("2026-05-24", 3L));
        when(statsService.aggregateActiveUsers(any(), any(), any())).thenReturn(buckets);

        mockMvc.perform(get("/api/v1/admin/stats/active-users")
                        .param("dimension", "DAILY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].bucket").value("2026-05-22"))
                .andExpect(jsonPath("$.data[0].activeUserCount").value(5))
                .andExpect(jsonPath("$.data[1].activeUserCount").value(8))
                .andExpect(jsonPath("$.data[2].activeUserCount").value(3));
    }

    @Test
    @DisplayName("response contains no forbidden privacy fields")
    void responseDoesNotLeakPrivateFields() throws Exception {
        when(statsService.aggregateActiveUsers(any(), any(), any())).thenReturn(List.of(
                new ActiveUserBucket("2026-05-24", 7L)));

        String body = mockMvc.perform(get("/api/v1/admin/stats/active-users")
                        .param("dimension", "DAILY"))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"email\""));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"title\""));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"description\""));
    }

    @Test
    @DisplayName("WEEKLY dimension passes through to service")
    void weeklyDimensionPassesThrough() throws Exception {
        when(statsService.aggregateActiveUsers(any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/admin/stats/active-users")
                        .param("dimension", "WEEKLY"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(statsService).aggregateActiveUsers("WEEKLY", null, null);
    }

    @Test
    @DisplayName("Range parameters are forwarded as ISO date strings")
    void rangeForwardedAsIsoStrings() throws Exception {
        when(statsService.aggregateActiveUsers(any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/admin/stats/active-users")
                        .param("dimension", "DAILY")
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(statsService)
                .aggregateActiveUsers("DAILY", "2026-05-01", "2026-05-31");
    }
}
