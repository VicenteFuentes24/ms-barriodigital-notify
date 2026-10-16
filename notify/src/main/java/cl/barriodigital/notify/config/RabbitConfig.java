package cl.barriodigital.notify.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.json.JsonFactory;

@Configuration
@EnableRabbit
@EnableConfigurationProperties(NotifyProperties.class)
public class RabbitConfig {
    @Bean
    SimpleRabbitListenerContainerFactory notifyListenerFactory(
            ConnectionFactory connectionFactory, NotifyProperties properties,
            @Value("${spring.rabbitmq.username}") String username,
            @Value("${spring.rabbitmq.password}") String password) {
        if (properties.listenersAutoStartup() && (username.isBlank() || password.isBlank())) {
            throw new IllegalArgumentException("Define RABBITMQ_USERNAME y RABBITMQ_PASSWORD para iniciar los consumidores.");
        }
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAutoStartup(properties.listenersAutoStartup());
        factory.setMissingQueuesFatal(true);
        return factory;
    }

    @Bean
    public JsonMapper notifyJsonMapper() {
        var factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(20).maxStringLength(4096).build())
                .build();
        return JsonMapper.builder(factory)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build();
    }
}
