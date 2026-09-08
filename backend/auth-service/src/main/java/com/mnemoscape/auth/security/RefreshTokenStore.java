package com.mnemoscape.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Refresh token 的 Redis 白名单 —— R14 主动撤销机制的核心存储。
 *
 * <p><b>Key 格式：</b>{@code jwt:refresh:{userId}:{tokenId}}，其中 {@code tokenId}
 * 即 refresh token 的 jti claim。Value 简单记一个非空标记位（"1"）即可，
 * 撤销语义由"key 是否存在"承担：删除 / 过期 = 该 jti 已不可用。
 *
 * <p><b>TTL：</b>默认 7 天（与 refresh token 过期时间一致）。key 自动随 TTL
 * 过期清理，无需人工 GC。
 *
 * <p><b>对比 R6 旧的 {@code jwt:blacklist:refresh:{jti}} 黑名单方案：</b>
 * <ul>
 *   <li>旧方案只记录"哪些 jti 已被撤销"，不感知"哪些 jti 合法存在"，无法做
 *       全用户撤销（"把所有该用户的 refresh token 全踢掉"）；需要遍历全表。</li>
 *   <li>新方案按 userId 分桶存活的 refresh token，{@link #revokeAllForUser(String)}
 *       一次 SCAN+DEL 完成该用户所有 refresh 撤销，O(N) 仅取决于该用户的 refresh 数，
 *       不受全站用户规模影响。</li>
 *   <li>jti 仍然唯一，每次签发都生成新 UUID，防重放（一次 refresh 后旧 token 立即被删除）。</li>
 * </ul>
 *
 * <p>Redis 不可用时，所有写入 / 删除操作吞掉异常并 WARN，遵循现有 RedisJwtBlacklist 的
 * "fail-open on read / best-effort on write" 策略 —— 写入失败不影响主流程，
 * 读取由上游 {@link com.mnemoscape.common.security.JwtTokenProvider#validateRefreshToken}
 * 的签名 + 过期校验兜底。
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    /** key 前缀。{userId} 是 sub claim，{tokenId} 是 jti claim。 */
    public static final String KEY_PREFIX = "jwt:refresh:";

    private final StringRedisTemplate redis;

    /** refresh token 默认有效期（毫秒）。由配置注入，默认 7 天 = 604800000 ms。 */
    private final long defaultTtlMillis;

    public RefreshTokenStore(StringRedisTemplate redis,
                             @Value("${mnemoscape.jwt.refresh-token-expiration:604800000}") long defaultTtlMillis) {
        this.redis = redis;
        this.defaultTtlMillis = defaultTtlMillis;
    }

    /**
     * 把刚刚签发的 refresh token 登记到白名单。后续校验会先看这里，再看黑名单。
     *
     * @param userId  JWT sub claim
     * @param tokenId refresh token 的 jti
     * @param ttl     距离 token 过期的毫秒数；{@code <= 0} 时使用默认 7 天 TTL
     */
    public void store(String userId, String tokenId, long ttl) {
        if (userId == null || userId.isBlank() || tokenId == null || tokenId.isBlank()) {
            return;
        }
        Duration duration = ttl > 0
                ? Duration.ofMillis(ttl)
                : Duration.ofMillis(defaultTtlMillis);
        try {
            redis.opsForValue().set(key(userId, tokenId), "1", duration);
        } catch (Exception e) {
            log.error("Failed to store refresh token userId={} tokenId={} reason={}",
                    userId, tokenId, e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 查询某个 refresh token 是否仍然合法（未撤销 / 未过期）。
     * Key 不存在 = 已撤销或已过期；返回 false。
     */
    public boolean isActive(String userId, String tokenId) {
        if (userId == null || userId.isBlank() || tokenId == null || tokenId.isBlank()) {
            return false;
        }
        try {
            Boolean exists = redis.hasKey(key(userId, tokenId));
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Refresh-store lookup failed userId={} tokenId={} reason={}; defaulting to inactive",
                    userId, tokenId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 撤销单个 refresh token（rotation 时使用：把旧 token 从白名单删掉）。
     * 立即生效，下一次 refresh 调用就会拒绝。
     */
    public void revoke(String userId, String tokenId) {
        if (userId == null || userId.isBlank() || tokenId == null || tokenId.isBlank()) {
            return;
        }
        try {
            redis.delete(key(userId, tokenId));
        } catch (Exception e) {
            log.error("Failed to revoke refresh token userId={} tokenId={} reason={}",
                    userId, tokenId, e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 撤销某用户的所有 refresh token —— R14 主动失效入口，常用于：
     * <ul>
     *   <li>用户主动"登出所有设备"；</li>
     *   <li>管理员强制踢人 / 改密码后让旧设备全部失效；</li>
     *   <li>账号被封禁时的兜底清理。</li>
     * </ul>
     *
     * <p>使用 SCAN 而不是 KEYS，避免阻塞 Redis 主线程；分桶粒度是 userId，
     * 一次扫描只扫该用户的 key，不影响全站其他用户。
     *
     * @return 实际被删除的 key 数量
     */
    public long revokeAllForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        String pattern = KEY_PREFIX + userId + ":*";
        List<String> collected = new ArrayList<>();
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(64).build();
            try (Cursor<byte[]> cursor = redis.getConnectionFactory()
                    .getConnection().scan(options)) {
                while (cursor.hasNext()) {
                    collected.add(new String(cursor.next()));
                }
            }
            if (collected.isEmpty()) {
                return 0L;
            }
            Long deleted = redis.delete(collected);
            log.info("Revoked all refresh tokens for userId={} count={}", userId, deleted);
            return deleted == null ? 0L : deleted;
        } catch (Exception e) {
            log.error("Failed to revoke all refresh tokens for userId={} reason={}",
                    userId, e.getClass().getSimpleName(), e);
            return 0L;
        }
    }

    /**
     * 列出某用户当前所有存活的 refresh token jti。供后台审计 / "我的设备"功能使用。
     * 测试也用得到。
     */
    public List<String> listTokenIdsForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        String pattern = KEY_PREFIX + userId + ":*";
        List<String> jtiList = new ArrayList<>();
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(64).build();
            try (Cursor<byte[]> cursor = redis.getConnectionFactory()
                    .getConnection().scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next());
                    int idx = key.lastIndexOf(':');
                    if (idx > 0 && idx < key.length() - 1) {
                        jtiList.add(key.substring(idx + 1));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Refresh-store scan failed userId={} reason={}",
                    userId, e.getClass().getSimpleName());
        }
        return jtiList;
    }

    private static String key(String userId, String tokenId) {
        return KEY_PREFIX + userId + ":" + tokenId;
    }
}