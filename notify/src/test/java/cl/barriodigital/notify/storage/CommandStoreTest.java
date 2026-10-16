package cl.barriodigital.notify.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import cl.barriodigital.notify.TestEvents;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.InvalidCommandException;
import cl.barriodigital.notify.event.RequestEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommandStoreTest {
    @TempDir Path directory;

    CommandStore store(int attempts) throws Exception {
        return new CommandStore(TestEvents.properties(directory, attempts), TestEvents.MAPPER, new SimpleMeterRegistry());
    }

    @Test
    void persistsIdempotencyAcrossRestart() throws Exception {
        var event = TestEvents.created();
        var calls = new AtomicInteger();
        assertEquals(CommandStore.Outcome.COMPLETED, store(3).execute(event, Command.CERTIFICATE, calls::incrementAndGet));
        assertEquals(CommandStore.Outcome.DUPLICATE, store(3).execute(event, Command.CERTIFICATE, calls::incrementAndGet));
        assertEquals(1, calls.get());
    }

    @Test
    void sameEventDifferentCommandIsIndependent() throws Exception {
        var event = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.RESUELTO);
        var calls = new AtomicInteger();
        var store = store(3);
        store.execute(event, Command.EMAIL, calls::incrementAndGet);
        store.execute(event, Command.CERTIFICATE, calls::incrementAndGet);
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsSameIdWithDifferentPayload() throws Exception {
        var original = TestEvents.created();
        var altered = new RequestEvent(original.type(), original.eventId(), original.timestamp(), original.traceId(),
                original.correlationId(), 999L, original.procedureTypeId(), original.status(), original.createdBy());
        var store = store(3);
        store.execute(original, Command.CERTIFICATE, () -> { });
        assertThrows(InvalidCommandException.class, () -> store.execute(altered, Command.CERTIFICATE, () -> fail("Must not execute")));
    }

    @Test
    void retriesTransientFailureThenCompletes() throws Exception {
        var calls = new AtomicInteger();
        var result = store(3).execute(TestEvents.created(), Command.CERTIFICATE, () -> {
            if (calls.incrementAndGet() < 3) { throw new java.io.IOException("Test failure"); }
        });
        assertEquals(CommandStore.Outcome.COMPLETED, result);
        assertEquals(3, calls.get());
    }

    @Test
    void exhaustionPersistsAndRedeliveryDoesNotResetLimit() throws Exception {
        var event = TestEvents.created();
        var calls = new AtomicInteger();
        CommandStore.Action fail = () -> { calls.incrementAndGet(); throw new java.io.IOException("Test failure"); };
        assertEquals(CommandStore.Outcome.EXHAUSTED, store(3).execute(event, Command.CERTIFICATE, fail));
        assertEquals(CommandStore.Outcome.EXHAUSTED, store(3).execute(event, Command.CERTIFICATE, fail));
        assertEquals(3, calls.get());
        var receipt = TestEvents.MAPPER.readValue(Files.readAllBytes(store(3).receiptPath(event, Command.CERTIFICATE)), CommandStore.Receipt.class);
        assertEquals(3, receipt.attempts());
        assertFalse(receipt.completed());
    }

    @Test
    void interruptionPreservesConsumedAttempt() throws Exception {
        var event = TestEvents.created();
        var store = store(3);
        assertThrows(InterruptedException.class, () -> store.execute(event, Command.CERTIFICATE, () -> { throw new InterruptedException(); }));
        store(3).execute(event, Command.CERTIFICATE, () -> { });
        var receipt = TestEvents.MAPPER.readValue(Files.readAllBytes(store.receiptPath(event, Command.CERTIFICATE)), CommandStore.Receipt.class);
        assertEquals(2, receipt.attempts());
    }

    @Test
    void concurrentDuplicateExecutesOnlyOnce() throws Exception {
        var store = store(3);
        var event = TestEvents.created();
        var calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> store.execute(event, Command.CERTIFICATE, calls::incrementAndGet));
            var b = executor.submit(() -> store.execute(event, Command.CERTIFICATE, calls::incrementAndGet));
            assertNotEquals(a.get(), b.get());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void corruptReceiptFailsClosed() throws Exception {
        var store = store(3);
        var event = TestEvents.created();
        AtomicFiles.write(store.receiptPath(event, Command.CERTIFICATE), "broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, () -> store.execute(event, Command.CERTIFICATE, () -> fail("Must not execute")));
    }
}
