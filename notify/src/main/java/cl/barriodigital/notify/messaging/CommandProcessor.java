package cl.barriodigital.notify.messaging;

import java.io.IOException;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.EventDecoder;
import cl.barriodigital.notify.event.InvalidCommandException;
import cl.barriodigital.notify.service.CertificateService;
import cl.barriodigital.notify.service.CrewTicketService;
import cl.barriodigital.notify.service.EmailService;
import cl.barriodigital.notify.storage.CommandStore;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
public class CommandProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CommandProcessor.class);
    private final EventDecoder decoder;
    private final CommandStore store;
    private final EmailService email;
    private final CrewTicketService crew;
    private final CertificateService certificate;
    private final MeterRegistry metrics;

    public CommandProcessor(EventDecoder decoder, CommandStore store, EmailService email,
                            CrewTicketService crew, CertificateService certificate, MeterRegistry metrics) {
        this.decoder = decoder;
        this.store = store;
        this.email = email;
        this.crew = crew;
        this.certificate = certificate;
        this.metrics = metrics;
    }

    public void process(Command command, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        CommandStore.Outcome outcome;
        String eventId;
        metrics.counter("notify.command.received", "command", command.routingKey()).increment();
        try {
            var event = decoder.decode(message, command);
            eventId = event.eventId();
            outcome = store.execute(event, command, () -> {
                switch (command) {
                    case EMAIL -> email.send(event);
                    case CREW -> crew.create(event);
                    case CERTIFICATE -> certificate.generate(event);
                }
            });
        } catch (InvalidCommandException ex) {
            reject(command, channel, tag, "invalid");
            return;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            channel.basicNack(tag, false, true);
            return;
        } catch (IOException | RuntimeException ex) {
            reject(command, channel, tag, "storage");
            return;
        }
        if (outcome == CommandStore.Outcome.EXHAUSTED) {
            reject(command, channel, tag, "exhausted");
            return;
        }
        // ACK is outside retry handling: a lost ACK must never repeat an already recorded action.
        channel.basicAck(tag, false);
        var result = outcome == CommandStore.Outcome.DUPLICATE ? "duplicate" : "completed";
        metrics.counter("notify.command." + result, "command", command.routingKey()).increment();
        LOG.info("ACK command={} eventId={} result={}", command.routingKey(), eventId, result);
    }

    private void reject(Command command, Channel channel, long tag, String reason) throws IOException {
        channel.basicNack(tag, false, false);
        metrics.counter("notify.command.rejected", "command", command.routingKey(), "reason", reason).increment();
        LOG.warn("NACK command={} requeue=false reason={}", command.routingKey(), reason);
    }
}
