package cl.barriodigital.notify;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import cl.barriodigital.notify.config.NotifyProperties;
import cl.barriodigital.notify.config.RabbitConfig;
import cl.barriodigital.notify.event.RequestEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

public final class TestEvents {
    public static final JsonMapper MAPPER = new RabbitConfig().notifyJsonMapper();
    private TestEvents() { }

    public static RequestEvent event(RequestEvent.Type type, RequestEvent.Status status) {
        return new RequestEvent(type, UUID.randomUUID().toString(), Instant.parse("2026-10-13T12:00:00Z"),
                "trace-test", "correlation-test", 42L, "retiro-escombros", status, "cliente@barriodigital.test");
    }

    public static RequestEvent created() {
        return event(RequestEvent.Type.REQUEST_CREATED, RequestEvent.Status.INGRESADO);
    }

    public static Message message(RequestEvent event) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryTag(7);
        properties.setMessageId(event.eventId());
        return new Message(MAPPER.writeValueAsBytes(event), properties);
    }

    public static NotifyProperties properties(Path path, int attempts) {
        return new NotifyProperties(path, "no-reply@barriodigital.test", attempts, 0, false);
    }
}
