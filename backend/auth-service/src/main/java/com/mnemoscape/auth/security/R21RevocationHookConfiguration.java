package com.mnemoscape.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.util.Map;

/**
 * R14 → R21 桥接：把 Spring Authorization Server 的事件转发到现有的
 * {@link TokenBlacklistService} / {@link RefreshTokenStore}。
 *
 * <p>两条桥接路径：
 * <ol>
 *   <li><b>/oauth2/revoke</b> —— 自定义
 *       {@link OAuth2TokenRevocationAuthenticationProvider} 包一层默认实现，
 *       成功后调 {@link TokenBlacklistService#revoke(String, long)} 写黑名单。</li>
 *   <li><b>refresh_token grant</b> —— 自定义
 *       {@link OAuth2RefreshTokenAuthenticationProvider} 包一层默认实现，
 *       成功后从旧 token 取 jti+sub 调
 *       {@link RefreshTokenStore#revoke(String, String)}，再把新 refresh
 *       token 写回 store。</li>
 * </ol>
 *
 * <p>注意：默认实现已经会把旧 refresh token 从 OAuth2AuthorizationService 删除
 * （"rotate" 语义），我们在这里叠加 R14 专属的 Redis 存储 —— 两套存储互不冲突，
 * 旧的 JwtAuthFilter 仍然可以从 RefreshTokenStore 校验。
 */
@Configuration
public class R21RevocationHookConfiguration {

    private static final Logger log = LoggerFactory.getLogger(R21RevocationHookConfiguration.class);

    private final TokenBlacklistService blacklist;
    private final RefreshTokenStore refreshStore;

    @Autowired
    public R21RevocationHookConfiguration(TokenBlacklistService blacklist,
                                          RefreshTokenStore refreshStore) {
        this.blacklist = blacklist;
        this.refreshStore = refreshStore;
    }

    /**
     * /oauth2/revoke 端点的桥接 Provider：默认逻辑之外，把被撤销的 access token
     * 的 jti 写进 R14 黑名单。这样下游旧的 JwtAuthFilter 仍能在 TTL 内拒绝该 jti。
     */
    @Bean
    public AuthenticationProvider r21RevocationAuthenticationProvider(
            OAuth2AuthorizationService authorizationService) {
        OAuth2TokenRevocationAuthenticationProvider defaultProvider =
                new OAuth2TokenRevocationAuthenticationProvider(authorizationService);
        return new RevocationHookProvider(defaultProvider, authorizationService, blacklist);
    }

    /**
     * refresh_token grant 的桥接 Provider：默认 rotate 之外，把旧 refresh jti
     * 从 RefreshTokenStore 删除，再把新 refresh jti 写进 store。
     */
    @Bean
    public AuthenticationProvider r21RefreshTokenAuthenticationProvider(
            OAuth2AuthorizationService authorizationService,
            OAuth2TokenGenerator<OAuth2Token> tokenGenerator) {
        OAuth2RefreshTokenAuthenticationProvider defaultProvider =
                new OAuth2RefreshTokenAuthenticationProvider(authorizationService, tokenGenerator);
        return new RefreshRotationHookProvider(defaultProvider, authorizationService, refreshStore);
    }

    /**
     * /oauth2/revoke 包装器。
     */
    static final class RevocationHookProvider implements AuthenticationProvider {
        private final OAuth2TokenRevocationAuthenticationProvider delegate;
        private final OAuth2AuthorizationService authService;
        private final TokenBlacklistService blacklist;

        RevocationHookProvider(OAuth2TokenRevocationAuthenticationProvider delegate,
                               OAuth2AuthorizationService authService,
                               TokenBlacklistService blacklist) {
            this.delegate = delegate;
            this.authService = authService;
            this.blacklist = blacklist;
        }

        @Override
        public Authentication authenticate(Authentication authentication)
                throws AuthenticationException {
            OAuth2TokenRevocationAuthenticationToken revocation =
                    (OAuth2TokenRevocationAuthenticationToken) authentication;
            Authentication result = delegate.authenticate(revocation);

            // 桥接到 R14 黑名单 —— 仅对 access token（refresh 走 RefreshTokenStore）
            String token = revocation.getToken();
            OAuth2Authorization authorization = findByToken(authService, token);
            if (authorization != null) {
                OAuth2Authorization.Token<OAuth2AccessToken> accessToken =
                        authorization.getToken(OAuth2AccessToken.class);
                if (accessToken != null && accessToken.getToken().getTokenValue().equals(token)) {
                    String jti = extractJti(authorization, accessToken);
                    long ttlMs = accessToken.getToken().getExpiresAt() != null
                            ? accessToken.getToken().getExpiresAt().toEpochMilli()
                                    - System.currentTimeMillis()
                            : 0L;
                    blacklist.revoke(jti, Math.max(ttlMs, 0L));
                    log.info("R21 revoke → R14 blacklist jti={} ttlMs={}", jti, ttlMs);
                }
            }
            return result;
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2TokenRevocationAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    /**
     * refresh_token grant 包装器。
     */
    static final class RefreshRotationHookProvider implements AuthenticationProvider {
        private final OAuth2RefreshTokenAuthenticationProvider delegate;
        private final OAuth2AuthorizationService authService;
        private final RefreshTokenStore refreshStore;

        RefreshRotationHookProvider(OAuth2RefreshTokenAuthenticationProvider delegate,
                                    OAuth2AuthorizationService authService,
                                    RefreshTokenStore refreshStore) {
            this.delegate = delegate;
            this.authService = authService;
            this.refreshStore = refreshStore;
        }

        @Override
        public Authentication authenticate(Authentication authentication)
                throws AuthenticationException {
            OAuth2RefreshTokenAuthenticationToken refreshAuth =
                    (OAuth2RefreshTokenAuthenticationToken) authentication;
            Authentication result = delegate.authenticate(refreshAuth);

            // R14 桥接：把旧 refresh jti 从 RefreshTokenStore 删除
            String presentedToken = refreshAuth.getRefreshToken();
            OAuth2Authorization oldAuth = authService.findByToken(
                    presentedToken, OAuth2TokenType.REFRESH_TOKEN);
            if (oldAuth != null) {
                String userId = oldAuth.getPrincipalName();
                OAuth2Authorization.Token<OAuth2RefreshToken> oldRefresh =
                        oldAuth.getRefreshToken();
                if (userId != null && oldRefresh != null) {
                    String oldJti = extractJti(oldAuth, oldRefresh);
                    refreshStore.revoke(userId, oldJti);
                    log.info("R21 refresh-rotate → R14 store.revoke userId={} jti={}",
                            userId, oldJti);
                }
            }
            // 把新 refresh token 写回 store
            if (result instanceof OAuth2AccessTokenAuthenticationToken accessResult) {
                OAuth2RefreshToken newRefresh = accessResult.getRefreshToken();
                if (newRefresh != null && accessResult.getPrincipal() != null) {
                    String userId = accessResult.getPrincipal() instanceof Authentication auth
                            ? auth.getName()
                            : String.valueOf(accessResult.getPrincipal());
                    String newJti = newRefresh.getTokenValue();
                    long ttlMs = newRefresh.getExpiresAt() != null
                            ? newRefresh.getExpiresAt().toEpochMilli() - System.currentTimeMillis()
                            : 0L;
                    refreshStore.store(userId, newJti, ttlMs);
                    log.info("R21 refresh-rotate → R14 store.store userId={} jti={}",
                            userId, newJti);
                }
            }
            return result;
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    /**
     * 从 OAuth2Authorization + token metadata 提取 jti。access token 是 JWT 时
     * metadata 由 {@code AuthorizationServerConfig.jwtTokenCustomizer()} 写入；
     * refresh token 是 Opaque（UUID 字符串）时 jti 直接等于 token value。
     */
    private static String extractJti(OAuth2Authorization authorization,
                                     OAuth2Authorization.Token<?> token) {
        if (authorization != null && token != null) {
            Map<String, Object> metadata = token.getMetadata();
            if (metadata != null && metadata.get("jti") != null) {
                return metadata.get("jti").toString();
            }
        }
        return token != null ? token.getToken().getTokenValue() : null;
    }

    /**
     * 在 AuthorizationService 里查找包含 token value 的 Authorization。
     */
    private static OAuth2Authorization findByToken(OAuth2AuthorizationService service,
                                                    String tokenValue) {
        OAuth2Authorization a = service.findByToken(tokenValue, OAuth2TokenType.ACCESS_TOKEN);
        if (a != null) return a;
        return service.findByToken(tokenValue, OAuth2TokenType.REFRESH_TOKEN);
    }
}
