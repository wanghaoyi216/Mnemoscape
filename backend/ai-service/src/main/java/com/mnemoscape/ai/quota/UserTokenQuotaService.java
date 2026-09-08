package com.mnemoscape.ai.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * <h1>per-user LLM Token 配额 (P0 R8)</h1>
 *
 * <p>借鉴墨问 {@code TokenQuota} 思路移植到 Java：Redis INCRBY 按 userId+自然日窗口
 * 记账 token。Redis 不可达时 fail-open 放行（AI 模块崩溃 vs 误杀正常用户，后者代价更大）。</p>
 *
 * <h2>设计权衡</h2>
 * <ul>
 *   <li><b>滑动窗口 vs 自然日窗口</b>：选自然日（INCRBY + EXPIRE）而非滑动窗口——
 *       LLM 配额语义是"今天用多少"，自然日更直观，且只需一条 ZSET 命令一次往返。</li>
 *   <li><b>悲观 vs 乐观</b>：选 INCRBY 后判超限（不在拿锁）—— Redis 单命令原子，并发精度可接受；省 SETNX 复杂度。</li>
 *   <li><b>fail-open</b>：Redis 抛异常一律放行 + log.warn。AI 模块对 Redis 是依赖不是强一致源，
 *       Redis 挂时让 LLM 直连降级、避免"无 AI 用"事故，比"严格超限"更重要。</li>
 * </ul>
 *
 * @author Mnemoscape R8
 */
@Slf4j
@Component
public class UserTokenQuotaService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;

    /** 默认每日上限，可被 application.yml 的 mnemoscape.ai.quota.daily-token-limit 覆盖 */
    @Value("${mnemoscape.ai.quota.daily-token-limit:200000}")
    private long dailyLimit;

    public UserTokenQuotaService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查并扣减 userId 当日配额。
     *
     * @param userId         网关 X-User-Id 头传入的业务用户 ID；null/blank 表示未登录或匿名
     * @param estimatedTokens 本次调用预估消耗的 token 数（粗算 question.length()/4）
     * @return true=通过；false=超限（调用方应返回友好错误事件）
     */
    public boolean tryAcquire(String userId, long estimatedTokens) {
        if (userId == null || userId.isBlank() || estimatedTokens <= 0) {
            return true; // 匿名 / 零 token 调用直接放行（不计入配额）
        }
        String key = "ai:token_quota:" + userId + ":" + LocalDate.now().format(DATE_FMT);
        try {
            Long current = redisTemplate.opsForValue().increment(key, estimatedTokens);
            if (current == null) {
                log.warn("[token-quota] Redis returned null, fail-open userId={}", userId);
                return true;
            }
            // 首次扣减时设置 EXPIRE 跨日（25h 留 1h 余量，应对时钟漂移）
            if (current == estimatedTokens) {
                redisTemplate.expire(key, 25, TimeUnit.HOURS);
            }
            if (current > dailyLimit) {
                log.warn("[token-quota] userId={} exceeded limit: {} > {} (today); denying",
                        userId, current, dailyLimit);
                // 不回滚（保持记账用于次日审计），仅返回 false 让上层走友好路径
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            // Redis 挂了：fail-open，但必须 WARN 运维侧
            log.warn("[token-quota] Redis unavailable, fail-open userId={} err={}",
                    userId, e.getClass().getSimpleName());
            return true;
        }
    }
}
