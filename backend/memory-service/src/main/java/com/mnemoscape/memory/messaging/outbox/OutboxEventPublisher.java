package com.mnemoscape.memory.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 写入门面 —— 在业务方法（{@code @Transactional}）里调用，把事件
 * 持久化到 outbox 表，与业务写库同事务提交。
 *
 * <p><b>方法语义</b>：
 * <ul>
 *   <li>{@link #stage(String, Object)} —— 必在活跃事务里被调用（{@code REQUIRED}），
 *       业务提交 → outbox 提交。业务回滚 → outbox 一起回滚，事件丢失符合预期。</li>
 *   <li>{@link #stageBestEffort(String, Object)} —— 没有活跃事务时使用，开自己的新事务
 *       把事件入 outbox；不抛异常。适合 fallback 路径（如 catch block 写补偿事件）。</li>
 * </ul>
 *
 * <p><b>非线程安全</b>：组件单例但只在 controller / service 单线程被调用，无问题。
 */
@Component
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在当前事务里 stage 一个事件。
     *
     * @param routingKey RabbitMQ routing key（不包含 exchange 前缀）
     * @param payload    事件对象，必须能被 Jackson 序列化
     * @return 写入的 {@code eventId}（UUID，consumer 端幂等键）
     * @throws JsonProcessingException 序列化失败（极少见；payload 含不可序列化对象时）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String stage(String routingKey, Object payload) throws JsonProcessingException {
        OutboxEvent event = buildEvent(routingKey, payload);
        repository.save(event);
        log.debug("[outbox] staged id={} routingKey={} payloadType={}",
                event.getId(), routingKey, event.getPayloadType());
        return event.getEventId();
    }

    /**
     * Fallback 版：自身开新事务（{@code REQUIRES_NEW}），业务事务已回滚或压根没事务时
     * 也能写 outbox。常用于 catch block 写补偿事件。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String stageBestEffort(String routingKey, Object payload) {
        try {
            OutboxEvent event = buildEvent(routingKey, payload);
            repository.save(event);
            return event.getEventId();
        } catch (Exception e) {
            log.warn("[outbox] best-effort stage failed routingKey={} reason={}",
                    routingKey, e.toString());
            return null;
        }
    }

    private OutboxEvent buildEvent(String routingKey, Object payload) throws JsonProcessingException {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRoutingKey(routingKey);
        event.setPayloadType(payload.getClass().getName());
        event.setPayload(objectMapper.writeValueAsString(payload));
        event.setCreatedAt(Instant.now());
        event.setNextAttemptAt(Instant.now());
        event.setAttempts(0);
        event.setStatus(OutboxEvent.Status.PENDING);
        return event;
    }
}
