package com.artis.saas_platform.common.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange provisioningExchange() {
        return new TopicExchange("provisioning.exchange");
    }

    @Bean public Queue provisioningCreateQueue() {
        return new Queue("provisioning.create", true);
    }

    @Bean public Queue provisioningMigrateQueue() {
        return new Queue("provisioning.migrate", true);
    }

    @Bean public Queue provisioningExpireQueue() {
        return new Queue("provisioning.expire", true);
    }

    @Bean public Binding createBinding() {
        return BindingBuilder.bind(provisioningCreateQueue())
                .to(provisioningExchange()).with("provisioning.create");
    }

    @Bean public Binding migrateBinding() {
        return BindingBuilder.bind(provisioningMigrateQueue())
                .to(provisioningExchange()).with("provisioning.migrate");
    }

    @Bean public Binding expireBinding() {
        return BindingBuilder.bind(provisioningExpireQueue())
                .to(provisioningExchange()).with("provisioning.expire");
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(messageConverter());
        return t;
    }
}