package com.mnemoscape.ai.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.constant.ServiceConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ai-service 端 RabbitMQ Consumer 配置。
 *
 * <p>与 memory-service {@code RabbitProducerConfig} 对称：
 * 共享同一份 {@link ServiceConstants} 常量 + Jackson JSON converter。
 *
 * <p><b>队列声明策略</b>：幂等声明（broker 不存在则建，存在则跳过字段设置）。
 * <ul>
 *   <li>{@link ServiceConstants#QUEUE_AI_MEMORY_INDEX}：监听
 *       {@code memory.indexed}，触发向量 upsert。</li>
 *   <li>{@link ServiceConstants#QUEUE_AI_MEMORY_EVICT}：监听
 *       {@code memory.deleted}，触发向量删除 + 搜索缓存清除。</li>
 * </ul>
 * 失败消息走 broker 自动声明的 {@code mnemoscape.events.dlx}（参见
 * {@code scripts/remote/rabbitmq/definitions.json}）。
 */
@Configuration
public class RabbitConsumerConfig {

    @Bean
    public MessageConverter mnemoscapeJsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public TopicExchange mnemoscapeEventsExchange() {
        return new TopicExchange(ServiceConstants.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Queue aiMemoryIndexQueue() {
        return QueueBuilder.durable(ServiceConstants.QUEUE_AI_MEMORY_INDEX)
                .withArgument("x-dead-letter-exchange", ServiceConstants.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", "ai.memory.index.dead")
                .build();
    }

    @Bean
    public Queue aiMemoryEvictQueue() {
        return QueueBuilder.durable(ServiceConstants.QUEUE_AI_MEMORY_EVICT)
                .withArgument("x-dead-letter-exchange", ServiceConstants.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", "ai.memory.evict.dead")
                .build();
    }

    @Bean
    public Binding bindAiMemoryIndex(Queue aiMemoryIndexQueue, TopicExchange mnemoscapeEventsExchange) {
        return BindingBuilder.bind(aiMemoryIndexQueue)
                .to(mnemoscapeEventsExchange)
                .with(ServiceConstants.RK_MEMORY_INDEXED);
    }

    @Bean
    public Binding bindAiMemoryEvict(Queue aiMemoryEvictQueue, TopicExchange mnemoscapeEventsExchange) {
        return BindingBuilder.bind(aiMemoryEvictQueue)
                .to(mnemoscapeEventsExchange)
                .with(ServiceConstants.RK_MEMORY_DELETED);
    }
}
