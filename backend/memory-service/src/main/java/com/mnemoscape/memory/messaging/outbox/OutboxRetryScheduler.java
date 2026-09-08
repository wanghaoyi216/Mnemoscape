package com.mnemoscape.memory.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.constant.ServiceConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbox 投递 worker —— 每 30s 扫一次 PENDING 事件推到 broker。
 *
 * <p><b>幂等保证</b>：
 * <ul>
 *   <li>eventId 全局唯一（{@link OutboxEvent#getEventId()}），consumer 端按它做
 *       "first-write-wins"，重复消费不产生副作用。</li>
 *   <li>PUBLISHED 后立即删表行（避免长尾留痕），删失败也无所谓 —— 反正不再被
 *       findReady() 选到。</li>
 *   <li>FAILED 行保留供审计，超过 10 次重试后不再尝试。</li>
 * </ul>
 *
 * <p><b>退避策略</b>：第 1 次重试 5s 后，第 2 次 10s，第 3 次 20s ... 第 N 次
 * min(5 * 2^N, 320)s。10 次后置 FAILED。运维看 FAILED 计数触发告警。
 *
 * <p><b>并发</b>：默认 30s 扫一次，批大小 50。如果并发量激增可调
 * {@code mnemoscape.outbox.batch-size}。
 */
@Component
@ConditionalOnProperty(value = "mnemoscape.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetryScheduler.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** Payload class cache —— 反射结果缓存，避免每次重试都 Class.forName。 */
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();

    @Value("${mnemoscape.outbox.batch-size:50}")
    private int batchSize;

    @Value("${mnemoscape.outbox.max-attempts:10}")
    private int maxAttempts;

    public OutboxRetryScheduler(OutboxEventRepository repository,
                                 RabbitTemplate rabbitTemplate,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onStartup(ContextRefreshedEvent ev) {
        long pending = repository.countByStatus(OutboxEvent.Status.PENDING);
        long failed = repository.countByStatus(OutboxEvent.Status.FAILED);
        log.info("[outbox] worker started pending={} failed={}", pending, failed);
    }

    /**
     * 30s 扫一次 —— 频率与 EventPublisher 的失败重试退避（5s-320s）对齐。
     * 如果 broker 长时间不可用，会在第 10 次重试时把事件置 FAILED，需要人工
     * 介入（避免无限堆积把 outbox 表撑爆）。
     */
    @Scheduled(fixedDelayString = "${mnemoscape.outbox.scan-interval-ms:30000}",
               initialDelayString = "${mnemoscape.outbox.initial-delay-ms:10000}")
    @Transactional
    public void dispatch() {
        List<OutboxEvent> batch = repository.findReady(
                OutboxEvent.Status.PENDING, Instant.now(), PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        int sent = 0, retried = 0, failed = 0;
        for (OutboxEvent event : batch) {
            try {
                Class<?> type = classCache.computeIfAbsent(event.getPayloadType(), this::safeLoad);
                if (type == null) {
                    markFailed(event, "unknown payload type " + event.getPayloadType());
                    failed++;
                    continue;
                }
                Object payload = objectMapper.readValue(event.getPayload(), type);
                rabbitTemplate.convertAndSend(ServiceConstants.EVENTS_EXCHANGE,
                        event.getRoutingKey(), payload);
                event.setStatus(OutboxEvent.Status.PUBLISHED);
                sent++;
            } catch (Exception e) {
                int newAttempts = event.getAttempts() + 1;
                event.setAttempts(newAttempts);
                event.setLastError(truncate(e.toString(), 500));
                if (newAttempts >= maxAttempts) {
                    event.setStatus(OutboxEvent.Status.FAILED);
                    failed++;
                    log.warn("[outbox] event {} permanently failed after {} attempts: {}",
                            event.getEventId(), newAttempts, e.toString());
                } else {
                    long backoffSec = Math.min(320L, 5L * (1L << Math.min(newAttempts, 6)));
                    event.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(backoffSec)));
                    retried++;
                }
            }
        }
        // saveAll 同时 UPDATE PENDING 行（attempts++, nextAttemptAt++）和 PUBLISHED 行
        // PUBLISHED 行后续 cleanup 任务（@Scheduled 另一轮）会删；这里先保留方便对账
        repository.saveAll(batch);
        if (sent + retried + failed > 0) {
            log.info("[outbox] dispatched batch sent={} retried={} failed={}", sent, retried, failed);
        }
    }

    /**
     * 5 分钟清一次已发表行 —— 防止 PENDING/FAILED 之外的 PUBLISHED 行无限堆积。
     * SELECT MAX(id) WHERE status=PUBLISHED → delete id <= maxId limit 1000
     * 避免一次性删太多撑爆 undo log。
     */
    @Scheduled(fixedDelayString = "${mnemoscape.outbox.cleanup-interval-ms:300000}",
               initialDelayString = "${mnemoscape.outbox.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanup() {
        // 简化：每次删前 1000 个 PUBLISHED 行（如果有的话）
        // 真要更精确可用 JPQL 限制：SELECT MAX(ID) FROM ... ORDER BY ID DESC LIMIT 1 OFFSET 1000
        // 这里用更简单：找最早 1000 个 PUBLISHED 的最大 id
        var topOldest = repository.findReady(OutboxEvent.Status.PUBLISHED, Instant.now().plusSeconds(365*24*3600L), PageRequest.of(0, 1000));
        if (topOldest.isEmpty()) return;
        long maxId = topOldest.get(topOldest.size() - 1).getId();
        int deleted = repository.deletePublishedOlderThan(OutboxEvent.Status.PUBLISHED, maxId);
        if (deleted > 0) {
            log.info("[outbox] cleanup removed {} published rows (maxId={})", deleted, maxId);
        }
    }

    private Class<?> safeLoad(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void markFailed(OutboxEvent event, String reason) {
        event.setStatus(OutboxEvent.Status.FAILED);
        event.setLastError(truncate(reason, 500));
        event.setAttempts(event.getAttempts() + 1);
    }
}
