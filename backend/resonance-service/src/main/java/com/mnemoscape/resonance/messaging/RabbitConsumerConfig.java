package com.mnemoscape.resonance.messaging;

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
 * resonance-service 端 RabbitMQ Consumer 配置。
 *
 * <p>监听三个路由键，把 memory-service 扇出的领域事件落地为：
 * <ul>
 *   <li>{@code resonance.driftbottle}  → 共鸣大厅 feed 失效（CacheEvict by 主题） + 双方通知</li>
 *   <li>{@code resonance.achievement} → 写 ChatMessage 系统通知 + admin top 缓存失效</li>
 *   <li>{@code resonance.memory.evict} → 共鸣大厅该用户参与的 feed 缓存失效</li>
 * </ul>
 * 死信均路由到 broker 全局 DLX {@code mnemoscape.events.dlx}。
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
    public Queue resonanceDriftBottleQueue() {
        return QueueBuilder.durable(ServiceConstants.QUEUE_RESONANCE_DRIFTBOTTLE)
                .withArgument("x-dead-letter-exchange", ServiceConstants.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", "resonance.driftbottle.dead")
                .build();
    }

    @Bean
    public Queue resonanceAchievementQueue() {
        return QueueBuilder.durable(ServiceConstants.QUEUE_RESONANCE_ACHIEVEMENT)
                .withArgument("x-dead-letter-exchange", ServiceConstants.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", "resonance.achievement.dead")
                .build();
    }

    @Bean
    public Queue resonanceMemoryEvictQueue() {
        return QueueBuilder.durable(ServiceConstants.QUEUE_RESONANCE_MEMORY_EVICT)
                .withArgument("x-dead-letter-exchange", ServiceConstants.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", "resonance.memory.evict.dead")
                .build();
    }

    @Bean
    public Binding bindResonanceDriftBottle(Queue resonanceDriftBottleQueue, TopicExchange mnemoscapeEventsExchange) {
        return BindingBuilder.bind(resonanceDriftBottleQueue).to(mnemoscapeEventsExchange)
                .with("driftbottle.#");
    }

    @Bean
    public Binding bindResonanceAchievement(Queue resonanceAchievementQueue, TopicExchange mnemoscapeEventsExchange) {
        return BindingBuilder.bind(resonanceAchievementQueue).to(mnemoscapeEventsExchange)
                .with(ServiceConstants.RK_ACHIEVEMENT_UNLOCKED);
    }

    @Bean
    public Binding bindResonanceMemoryEvict(Queue resonanceMemoryEvictQueue, TopicExchange mnemoscapeEventsExchange) {
        return BindingBuilder.bind(resonanceMemoryEvictQueue).to(mnemoscapeEventsExchange)
                .with(ServiceConstants.RK_MEMORY_DELETED);
    }
}
