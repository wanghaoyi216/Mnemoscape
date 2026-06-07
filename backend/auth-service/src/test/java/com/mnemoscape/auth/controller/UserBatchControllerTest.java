package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.repository.IdUsernameProjection;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link UserBatchController#batchUsernames}
 * (admin-dashboard task 6.9, validates Requirements 10.2 / 10.4).
 *
 * <p>Verifies the strict-whitelist contract:
 * <ul>
 *   <li>Returns {@code username} only — never email, role, passwordHash, etc.</li>
 *   <li>Ids that don't exist in the repo are silently absent from the
 *       {@code usernames} map (caller fallback responsibility).</li>
 *   <li>The endpoint accepts up to 100 ids per request.</li>
 *   <li>Empty input returns an empty map.</li>
 *   <li>Duplicate ids in the request are de-duplicated server-side.</li>
 * </ul>
 */
class UserBatchControllerTest {

    private MockMvc mockMvc;
    private UserRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        UserBatchController controller = new UserBatchController(repo);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("returns a id→username map for known ids")
    void mapsKnownIdsToUsernames() throws Exception {
        when(repo.findIdUsernameByIdIn(any())).thenReturn(List.of(
                new IdUsernameProjection("u-1", "alice"),
                new IdUsernameProjection("u-2", "bob")));

        String body = mapper.writeValueAsString(Map.of("userIds", List.of("u-1", "u-2")));

        mockMvc.perform(post("/api/v1/users/batch-usernames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.usernames.u-1").value("alice"))
                .andExpect(jsonPath("$.data.usernames.u-2").value("bob"));
    }

    @Test
    @DisplayName("missing ids are silently absent from the map")
    void missingIdsAbsentFromMap() throws Exception {
        when(repo.findIdUsernameByIdIn(any())).thenReturn(List.of(
                new IdUsernameProjection("u-1", "alice")));

        String body = mapper.writeValueAsString(Map.of("userIds", List.of("u-1", "u-missing")));

        mockMvc.perform(post("/api/v1/users/batch-usernames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usernames.u-1").value("alice"))
                .andExpect(jsonPath("$.data.usernames.u-missing").doesNotExist());
    }

    @Test
    @DisplayName("response JSON contains no email / passwordHash / role / avatarUrl")
    void responseHasNoSensitiveFields() throws Exception {
        when(repo.findIdUsernameByIdIn(any())).thenReturn(List.of(
                new IdUsernameProjection("u-1", "alice")));

        String body = mapper.writeValueAsString(Map.of("userIds", List.of("u-1")));

        String responseBody = mockMvc.perform(post("/api/v1/users/batch-usernames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(responseBody.contains("\"email\""),
                "email must never leak: " + responseBody);
        assertFalse(responseBody.contains("\"passwordHash\""),
                "passwordHash must never leak: " + responseBody);
        assertFalse(responseBody.contains("\"role\""),
                "role must never leak: " + responseBody);
        assertFalse(responseBody.contains("\"avatarUrl\""),
                "avatarUrl must never leak: " + responseBody);
        assertFalse(responseBody.contains("\"verified\""),
                "verified must never leak: " + responseBody);
    }

    @Test
    @DisplayName("empty userIds list returns empty map")
    void emptyListReturnsEmptyMap() throws Exception {
        String body = mapper.writeValueAsString(Map.of("userIds", List.of()));

        mockMvc.perform(post("/api/v1/users/batch-usernames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usernames").isMap());
    }

    @Test
    @DisplayName("duplicates in input are de-duped before SQL lookup")
    void duplicateIdsAreDeduped() throws Exception {
        when(repo.findIdUsernameByIdIn(any())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            // The controller wraps the request list in a LinkedHashSet, so
            // exactly one of each id should reach the repo.
            org.junit.jupiter.api.Assertions.assertEquals(1, ids.size(),
                    "controller must dedupe the request list");
            return List.of(new IdUsernameProjection("u-1", "alice"));
        });

        String body = mapper.writeValueAsString(
                Map.of("userIds", List.of("u-1", "u-1", "u-1")));

        mockMvc.perform(post("/api/v1/users/batch-usernames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usernames.u-1").value("alice"));
    }

    @Test
    @DisplayName("100 ids are accepted (cap is 100)")
    void hundredIdsAccepted() throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) ids.add("u-" + i);
        when(repo.findIdUsernameByIdIn(any())).thenReturn(List.of());

        String body = mapper.writeValueAsString(Map.of("userIds", ids));
        mockMvc.perform(post("/api/v1/users/batch-usernames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("101 ids are rejected with 400 (cap enforcement)")
    void overOneHundredIdsRejected() throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) ids.add("u-" + i);

        String body = mapper.writeValueAsString(Map.of("userIds", ids));
        mockMvc.perform(post("/api/v1/users/batch-usernames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
