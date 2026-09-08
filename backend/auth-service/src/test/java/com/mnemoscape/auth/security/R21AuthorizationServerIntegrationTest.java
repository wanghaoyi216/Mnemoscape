package com.mnemoscape.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R21 端到端测试 —— 客户端凭证模式发 token，再用 token 调 /userinfo 拿到 user info。
 *
 * <p>测试覆盖：
 * <ol>
 *   <li>POST /oauth2/token (grant_type=client_credentials) 拿到 access_token</li>
 *   <li>GET /userinfo 携带 Bearer token，返回 OIDC 标准 userinfo JSON</li>
 *   <li>校验返回的 sub / preferred_username / role 字段</li>
 *   <li>JWKS 端点暴露 RSA 公钥</li>
 *   <li>OIDC discovery endpoint 暴露 issuer / endpoints</li>
 * </ol>
 *
 * <p>本测试用 {@code @AutoConfigureMockMvc} 走 Spring Boot 的标准 MockMvc
 * 自动装配，避开手写 SecurityMockMvcConfigurers。
 */
@SpringBootTest(
        classes = {AuthorizationServerConfig.class, R21RevocationHookConfiguration.class,
                SecurityConfig.class,
                com.mnemoscape.auth.AuthApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                "com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration," +
                "com.alibaba.cloud.nacos.endpoint.NacosDiscoveryEndpointAutoConfiguration," +
                "com.alibaba.cloud.nacos.config.NacosConfigAutoConfiguration",
        "jasypt.encryptor.password=test",
        "mnemoscape.jwt.secret=ZmFrZS1zZWNyZXQtZm9yLXRlc3RpbmctcHVycG9zZXMtb25seQ==",
        "mnemoscape.jwt.access-token-expiration=3600000",
        "mnemoscape.jwt.refresh-token-expiration=604800000",
        "admin.bootstrap-secret="
})
class R21AuthorizationServerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // MockMvc 通过 @AutoConfigureMockMvc 自动注入
    }

    @Test
    @DisplayName("client_credentials: POST /oauth2/token → GET /userinfo 拿到 user info")
    void clientCredentials_thenUserInfo() throws Exception {
        // 1. client_credentials 模式发 token
        String credentials = Base64.getEncoder().encodeToString(
                "memory-service:memory-service-secret".getBytes(StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue());
        form.add(OAuth2ParameterNames.SCOPE, "openid profile internal.read");

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(form))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode tokenJson = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        String accessToken = tokenJson.get("access_token").asText();
        String tokenType = tokenJson.get("token_type").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(tokenType.toLowerCase()).isEqualTo(OAuth2AccessToken.TokenType.BEARER.getValue().toLowerCase());

        // 2. 用 access token 调 /userinfo
        MvcResult userInfoResult = mockMvc.perform(get("/userinfo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode userInfo = objectMapper.readTree(userInfoResult.getResponse().getContentAsString());
        // OIDC 标准字段：sub 必有；preferred_username / role 是我们自定义的
        assertThat(userInfo.has("sub")).isTrue();
        assertThat(userInfo.get("sub").asText()).isNotBlank();
        // client_credentials 模式：principal 是 client id，本测试里就是 "memory-service"
        assertThat(userInfo.get("preferred_username").asText()).isEqualTo("memory-service");
        assertThat(userInfo.get("role").asText()).isIn("USER", "ADMIN");
    }

    @Test
    @DisplayName("JWKS 端点暴露 RSA 公钥")
    void jwksEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jwks = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(jwks.has("keys")).isTrue();
        JsonNode keys = jwks.get("keys");
        assertThat(keys.isArray()).isTrue();
        assertThat(keys.size()).isGreaterThanOrEqualTo(1);
        JsonNode key = keys.get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("kid")).isTrue();
        assertThat(key.has("n")).isTrue();
        assertThat(key.has("e")).isTrue();
        // 私钥不应当暴露
        assertThat(key.has("d")).isFalse();
    }

    @Test
    @DisplayName("OIDC discovery 端点")
    void oidcDiscovery() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode discovery = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(discovery.get("issuer").asText()).isEqualTo("http://localhost:8081");
        assertThat(discovery.get("token_endpoint").asText()).isEqualTo("http://localhost:8081/oauth2/token");
        assertThat(discovery.get("jwks_uri").asText()).isEqualTo("http://localhost:8081/oauth2/jwks");
        assertThat(discovery.get("userinfo_endpoint").asText()).isEqualTo("http://localhost:8081/userinfo");
        assertThat(discovery.get("revocation_endpoint").asText()).isEqualTo("http://localhost:8081/oauth2/revoke");
    }

    @Test
    @DisplayName("没有 token 调 /userinfo 必须 401")
    void userInfoRequiresAuth() throws Exception {
        mockMvc.perform(get("/userinfo"))
                .andExpect(status().is(HttpStatus.UNAUTHORIZED.value()));
    }
}
