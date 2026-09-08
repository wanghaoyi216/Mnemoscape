package com.mnemoscape.memory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * memory-service 端 RabbitMQ Producer 配置。
 *
 * <p>关键点：
 * <ul>
 *   <li>消息体走 Jackson JSON — 与 ai-service / resonance-service Consumer 端
 *       默认的 {@link Jackson2JsonMessageConverter} 互通；Consumer 用 record DTO
 *       直接反序列化，类型由 {@code __TypeId__} header 携带。</li>
 *   <li>RabbitTemplate 配 retry：3 次指数退避，让 broker 短暂不可用时不丢消息；
 *       彻底失败由 {@link com.mnemoscape.memory.messaging.EventPublisher} catch
 *       后做 Feign 兜底（"双轨"过渡阶段）。</li>
 *   <li><b>不开 publisher confirms</b>：当前 broker 配 classic queue + 业务对
 *       至少一次容忍度高（索引重做也只是浪费一次 embedding，幂等键 = memoryId 兜住）；
 *       开 confirms 会引入额外的 ack 协议复杂度，等真正高一致性场景再加。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RabbitProducerConfig {

    @Bean
    public MessageConverter mnemoscapeJsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate mnemoscapeRabbitTemplate(ConnectionFactory connectionFactory,
                                                   MessageConverter mnemoscapeJsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(mnemoscapeJsonMessageConverter);
        template.setRetryTemplate(buildRetryTemplate());
        // mandatory + returnsCallback：路由不到队列的消息不静默丢失，记录日志便于排查
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.warn(
                "[mq] message returned (unroutable): replyCode={} replyText={} exchange={} routingKey={}",
                returned.getReplyCode(), returned.getReplyText(),
                returned.getExchange(), returned.getRoutingKey()));
        // 默认 routing 用空，让 EventPublisher 显式传 routingKey
        return template;
    }

    private static RetryTemplate buildRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(300);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2_000);
        retryTemplate.setBackOffPolicy(backOff);
        // 3 次重试 = 1 次首发 + 2 次重发
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(3));
        return retryTemplate;
    }

    /** Spring AMQP listener container 的"放弃 requeue"策略 —
     *  Consumer 端用得到（避免坏消息无限循环），Producer 端不影响。
     *  这里集中暴露 bean 是为了 ai-service / resonance-service 复用同一份策略。 */
    @Bean
    public RejectAndDontRequeueRecoverer mnemoscapeMessageRecoverer() {
        return new RejectAndDontRequeueRecoverer();
    }
}
