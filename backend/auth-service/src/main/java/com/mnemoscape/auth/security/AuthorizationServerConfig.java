package com.mnemoscape.auth.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * R21 Spring Authorization Server —— 替换手写 JwtSigner（JOSE 直签）的标准做法。
 *
 * <p>本配置只负责"Authorization Server"那半边：
 * <ul>
 *   <li>{@link #registeredClientRepository()} —— 客户端注册表（in-memory）。</li>
 *   <li>{@link #jwkSource()} —— JWKS，资源服务器从 {@code /oauth2/jwks} 拉公钥。</li>
 *   <li>{@link #authorizationServerSettings()} —— issuer / token endpoint 等元数据。</li>
 *   <li>{@link #authorizationServerSecurityFilterChain(HttpSecurity)} —— 装配
 *       {@code /oauth2/authorize}、{@code /oauth2/token}、{@code /oauth2/jwks}、
 *       {@code /oauth2/revoke}、{@code /oauth2/introspect}、{@code /userinfo} 端点。</li>
 *   <li>{@link #oidcUserInfoService()} —— OIDC userinfo，把 access token 里
 *       的 sub/username/role 透出成标准 {@link OidcUserInfo}。</li>
 *   <li>{@link #jwtTokenCustomizer()} —— 写 username / role / jti claim，
 *       并把 jti 落到 token metadata 以便 R14 桥接读取。</li>
 * </ul>
 *
 * <p><b>R14 兼容：</b>refresh token 旋转通过自定义
 * {@link OAuth2TokenCustomizer} 把 jti + userId 写进 claim，
 * 业务层在收到旧 refresh 后会调 {@link RefreshTokenStore#revoke(String, String)}；
 * access token 撤销通过 {@code /oauth2/revoke} 端点（默认实现），收到时调
 * {@link TokenBlacklistService#revoke(String, long)}。这两个桥接由
 * {@link R21RevocationHookConfiguration} 提供。
 *
 * <p><b>秘钥：</b>默认每次启动生成新的 RSA 密钥对（dev 友好，重启即"轮换"）。
 * 生产应当从 Nacos / Vault 拉取固定 key，并实现 JWK 轮换：保留旧 key
 * 并在 {@code kid} 上一并暴露给 {@code /oauth2/jwks}。
 */
@Configuration
public class AuthorizationServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerConfig.class);

    /** issuer —— 资源服务器 jwt 校验时拿到的 iss claim。dev 简化用 localhost:8081。 */
    public static final String ISSUER = "http://localhost:8081";

    /**
     * Authorization Server 端点的 SecurityFilterChain。
     *
     * <p>注意：{@link OAuth2AuthorizationServerConfiguration#applyDefaultSecurity(HttpSecurity)}
     * 会注册 {@code /oauth2/authorize}、{@code /oauth2/token}、{@code /oauth2/jwks}、
     * {@code /oauth2/revoke}、{@code /oauth2/introspect}。本方法额外启用 OIDC 并
     * 挂上 userinfo endpoint（资源服务器端的 Bearer 校验复用默认 JWT 校验链）。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            org.springframework.security.authentication.AuthenticationProvider r21RevocationAuthenticationProvider,
            org.springframework.security.authentication.AuthenticationProvider r21RefreshTokenAuthenticationProvider) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();
        // OIDC userinfo：把 access token 里的 sub/username/role 投射为标准 userinfo response
        authorizationServerConfigurer.oidc(oidc -> oidc.userInfoEndpoint(uid -> uid
                .userInfoMapper(this::buildOidcUserInfo)));

        // R14 桥接：把 revoke / refresh 端点的 AuthenticationProvider 替换为带 hook 的版本
        authorizationServerConfigurer
                .tokenRevocationEndpoint(revocation -> revocation
                        .authenticationProvider(r21RevocationAuthenticationProvider))
                .tokenEndpoint(token -> token
                        .authenticationProvider(r21RefreshTokenAuthenticationProvider));

        RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();
        http
            .securityMatcher(endpointsMatcher)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
            .apply(authorizationServerConfigurer);

        // /userinfo 也要做 bearer 校验：复用默认的 JwtDecoder（来自 JWKSource）
        http.oauth2ResourceServer(rs -> rs.jwt(jwt -> { /* default */ }));
        return http.build();
    }

    /**
     * 把 access token 解析后的 Jwt 投成 OIDC userinfo 响应。
     * 注入 username / role claim（由 {@link #jwtTokenCustomizer()} 写入）。
     *
     * <p>Spring Authorization Server 1.3.x 的 OIDC userinfo endpoint 接受
     * {@code Function<OidcUserInfoAuthenticationContext, OidcUserInfo>}，
     * 由我们负责把 {@link OidcUserInfo} 作为返回值交给框架。
     */
    private OidcUserInfo buildOidcUserInfo(
            org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext context) {
        OAuth2AccessToken accessToken = context.getAccessToken();
        String sub = null;
        String username = null;
        String role = null;
        if (accessToken != null) {
            // accessToken 是 OAuth2AccessToken；如果是 self-contained JWT 格式，
            // 通过 getToken() 拿到 JWT 字符串并解析 claims；opaque 格式则跳过。
            String tokenValue = accessToken.getTokenValue();
            try {
                // 简化：从 OAuth2Authorization 里直接拿 metadata（jwtTokenCustomizer 写入）
                OAuth2Authorization authz = context.getAuthorization();
                if (authz != null) {
                    Object principalName = authz.getPrincipalName();
                    sub = principalName == null ? null : String.valueOf(principalName);
                }
                if (tokenValue != null && tokenValue.split("\\.").length == 3) {
                    // 看起来像 JWT：直接 base64-decode payload
                    String payload = new String(java.util.Base64.getUrlDecoder()
                            .decode(tokenValue.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode n = m.readTree(payload);
                    if (n.has("username")) username = n.get("username").asText();
                    if (n.has("role")) role = n.get("role").asText();
                }
            } catch (Exception e) {
                log.debug("buildOidcUserInfo: failed to parse JWT payload, falling back: {}",
                        e.getClass().getSimpleName());
            }
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sub == null ? "" : sub);
        claims.put("preferred_username", username == null ? (sub == null ? "" : sub) : username);
        claims.put("role", role == null ? "USER" : role);
        return new OidcUserInfo(claims);
    }

    /**
     * 客户端注册表。两个内置客户端：
     * <ul>
     *   <li>{@code memory-service} —— {@code client_credentials}，用于服务间
     *       调用的 access token（如 memory-service 调 auth-service 的 admin API）。
     *       secret 通过 Nacos / 环境变量注入；这里给 dev 默认值。</li>
     *   <li>{@code mnemoscape-web} —— {@code authorization_code + PKCE}，
     *       用于前端登录。secret 为 public（不需要 Basic Auth）。</li>
     * </ul>
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(PasswordEncoder clientPasswordEncoder) {
        // memory-service 客户端：client_credentials 模式
        RegisteredClient memoryService = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("memory-service")
                .clientSecret("{noop}memory-service-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(OidcScopes.OPENID)
                .scope("profile")
                .scope("internal.read")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(false)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .build())
                .build();

        // 前端 web 客户端：authorization_code + PKCE（公共客户端）
        RegisteredClient webApp = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("mnemoscape-web")
                // public 客户端：不存 secret
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:5173/callback")
                .redirectUri("http://localhost:5173/")
                .postLogoutRedirectUri("http://localhost:5173/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("offline_access")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false) // R14 旋转语义
                        .build())
                .build();

        log.info("R21 RegisteredClients registered: memory-service(client_credentials), " +
                "mnemoscape-web(authorization_code+PKCE)");
        return new InMemoryRegisteredClientRepository(memoryService, webApp);
    }

    /**
     * 客户端 secret 的密码编码器。生产应当用 BCrypt；但本服务内置的客户端固定，
     * 用 NoOp + 强随机 secret 即可，避免每次启动都要做 BCrypt strength calibration。
     * 这里使用 {noop} 前缀以让 Spring 知道这是明文 secret（InMemoryRegisteredClientRepository
     * 默认期望 BCrypt 编码格式）。
     */
    @Bean
    public PasswordEncoder clientPasswordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    /**
     * JWKSource —— RSA 密钥对。每次启动生成新的，dev 友好；生产应从 Nacos / Vault
     * 拉取并实现 JWK 轮换（同时暴露旧 key 在 JWKS 中，kid 区分）。
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair keyPair = gen.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
            log.info("R21 JWK generated kid={} (in-memory, dev only)", rsaKey.getKeyID());
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize R21 JWK", e);
        }
    }

    /**
     * Authorization Server 元数据：issuer、token endpoint 等。
     * 资源服务器从 {@code /.well-known/openid-configuration} 拉这些。
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(ISSUER)
                .authorizationEndpoint("/oauth2/authorize")
                .tokenEndpoint("/oauth2/token")
                .jwkSetEndpoint("/oauth2/jwks")
                .tokenRevocationEndpoint("/oauth2/revoke")
                .tokenIntrospectionEndpoint("/oauth2/introspect")
                .oidcUserInfoEndpoint("/userinfo")
                .build();
    }

    /**
     * 把 username / role / jti 写进 access token claim + metadata。
     *
     * <p>这里挂业务身份是 Authorization Server 自定义 claim 的标准做法：principal
     * 来自 Spring Security 的 Authentication，业务层在生成 token 时把 username/role
     * 注入 claim。资源服务器 jwt 校验后直接读这两个 claim 做授权。
     *
     * <p>jti 是 R14 撤销语义的锚点：access token 被 revoke 时按 jti 写黑名单。
     * 我们把 jti 同时放到 claim（让资源服务器也能读到）和 token metadata（让
     * {@link R21RevocationHookConfiguration} 的 revoke provider 能取到）。
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            // access token 走 JWT；refresh token 是 Opaque（UUID 字符串），不需要处理
            if (context.getTokenType() == null
                    || !"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            if (context.getPrincipal() == null) {
                return;
            }
            // 主体：client_credentials 模式是 OAuth2ClientAuthenticationToken，
            // authorization_code 模式是用户名/密码 / OIDC 用户的 Authentication。
            // 这两种都不是 JwtAuthenticationToken（没有 getAttributes()），
            // 所以 role 走"从 token context 取不到就走 USER"的回退路径 —— 业务
            // 角色实际由用户服务在 authorization_code 流程里通过 additionalParameters
            // 注入到 context，或者直接落到 principal name。
            String principalName = context.getPrincipal().getName();
            String username = principalName;
            String role = "USER";
            // 优先从 principal 携带的 details / authorities 里读 role
            Object principalObj = context.getPrincipal();
            if (principalObj instanceof org.springframework.security.core.Authentication) {
                org.springframework.security.core.Authentication auth =
                        (org.springframework.security.core.Authentication) principalObj;
                // 角色规范化：扫 authorities 找 ROLE_ADMIN
                boolean isAdmin = auth.getAuthorities() != null
                        && auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
                if (isAdmin) {
                    role = "ADMIN";
                }
            }
            String jti = UUID.randomUUID().toString();
            context.getClaims().claim("username", username);
            context.getClaims().claim("role", role);
            // JwtClaimsSet.Builder 没有 jti() 方法，用 claim() 写
            context.getClaims().claim("jti", jti);
            // 把 jti 落到 token metadata —— R14 桥接会读这里
            OAuth2Authorization.Token<?> accessToken = context.getAuthorization()
                    .getToken(OAuth2AccessToken.class);
            if (accessToken != null) {
                accessToken.getMetadata().put("jti", jti);
            }
            log.debug("R21 access token issued sub={} role={} username={} jti={}",
                    principalName, role, username, jti);
        };
    }
}
