package cl.barriodigital.notify.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import cl.barriodigital.notify.config.NotifyProperties;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.InvalidCommandException;
import cl.barriodigital.notify.event.RequestEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CommandStore {
    public enum Outcome { COMPLETED, DUPLICATE, EXHAUSTED }
    public record Receipt(String fingerprint, int attempts, boolean completed, Instant updatedAt) { }
    @FunctionalInterface
    public interface Action { void run() throws Exception; }

    private final NotifyProperties properties;
    private final JsonMapper mapper;
    private final MeterRegistry metrics;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public CommandStore(NotifyProperties properties, JsonMapper mapper, MeterRegistry metrics) throws IOException {
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = metrics;
        Files.createDirectories(directory("receipts"));
        Files.createDirectories(directory("tickets"));
        Files.createDirectories(directory("certificates"));
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public Path directory(String name) {
        if (!name.equals("receipts") && !name.equals("tickets") && !name.equals("certificates")) {
            throw new IllegalArgumentException("Directorio no permitido.");
        }
        return properties.storageDirectory().toAbsolutePath().normalize().resolve(name);
    }

    public String key(RequestEvent event, Command command) {
        return UUID.fromString(event.eventId()).toString() + "." + command.name().toLowerCase(java.util.Locale.ROOT);
    }

    public Path receiptPath(RequestEvent event, Command command) {
        return directory("receipts").resolve(key(event, command) + ".json");
    }

    public Outcome execute(RequestEvent event, Command command, Action action) throws IOException, InterruptedException {
        var key = key(event, command);
        var localLock = locks[Math.floorMod(key.hashCode(), locks.length)];
        localLock.lockInterruptibly();
        try (var file = FileChannel.open(directory("receipts").resolve(key + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var fileLock = file.lock()) {
            var path = receiptPath(event, command);
            var fingerprint = fingerprint(event);
            var receipt = Files.exists(path) ? mapper.readValue(Files.readAllBytes(path), Receipt.class)
                    : new Receipt(fingerprint, 0, false, Instant.now());
            if (receipt == null || !fingerprint.equals(receipt.fingerprint()) || receipt.attempts() < 0) {
                throw new InvalidCommandException();
            }
            if (receipt.completed()) {
                return Outcome.DUPLICATE;
            }
            while (receipt.attempts() < properties.maxAttempts()) {
                receipt = new Receipt(fingerprint, receipt.attempts() + 1, false, Instant.now());
                AtomicFiles.write(path, mapper.writeValueAsBytes(receipt));
                try {
                    action.run();
                } catch (InvalidCommandException ex) {
                    throw ex;
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    if (receipt.attempts() < properties.maxAttempts()) {
                        metrics.counter("notify.command.retries", "command", command.routingKey()).increment();
                        Thread.sleep(properties.retryDelayMs());
                    }
                    continue;
                }
                AtomicFiles.write(path, mapper.writeValueAsBytes(
                        new Receipt(fingerprint, receipt.attempts(), true, Instant.now())));
                return Outcome.COMPLETED;
            }
            return Outcome.EXHAUSTED;
        } finally {
            localLock.unlock();
        }
    }

    private String fingerprint(RequestEvent event) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(event)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible.", ex);
        }
    }
}
