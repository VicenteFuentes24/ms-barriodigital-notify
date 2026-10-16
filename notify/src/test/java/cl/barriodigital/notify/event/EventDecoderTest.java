package cl.barriodigital.notify.event;

import java.nio.charset.StandardCharsets;
import cl.barriodigital.notify.TestEvents;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.junit.jupiter.api.Assertions.*;

class EventDecoderTest {
    static ValidatorFactory factory;
    static EventDecoder decoder;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        decoder = new EventDecoder(TestEvents.MAPPER, factory.getValidator());
    }

    @AfterAll
    static void close() { factory.close(); }

    @ParameterizedTest
    @CsvSource({"REQUEST_CREATED,INGRESADO,CERTIFICATE", "REQUEST_STATUS_CHANGED,ADMITIDO,EMAIL",
            "REQUEST_STATUS_CHANGED,EN_TERRENO,CREW", "REQUEST_STATUS_CHANGED,RESUELTO,EMAIL",
            "REQUEST_STATUS_CHANGED,RESUELTO,CERTIFICATE"})
    void acceptsProducerCommands(RequestEvent.Type type, RequestEvent.Status status, Command command) {
        var event = TestEvents.event(type, status);
        assertEquals(event, decoder.decode(TestEvents.message(event), command));
    }

    @ParameterizedTest
    @CsvSource({"REQUEST_CREATED,INGRESADO,EMAIL", "REQUEST_STATUS_CHANGED,RECHAZADO,EMAIL",
            "REQUEST_STATUS_CHANGED,EN_GESTION,CREW", "REQUEST_STATUS_CHANGED,ADMITIDO,CERTIFICATE"})
    void rejectsIncompatibleCommands(RequestEvent.Type type, RequestEvent.Status status, Command command) {
        assertThrows(InvalidCommandException.class,
                () -> decoder.decode(TestEvents.message(TestEvents.event(type, status)), command));
    }

    @Test
    void readsActualRequestsIsoJsonWithoutJavaTypeHeader() {
        var event = TestEvents.created();
        var json = "{\"type\":\"REQUEST_CREATED\",\"eventId\":\"" + event.eventId()
                + "\",\"timestamp\":\"2026-10-13T12:00:00Z\",\"traceId\":\"trace-test\","
                + "\"correlationId\":\"correlation-test\",\"requestId\":42,"
                + "\"procedureTypeId\":\"retiro-escombros\",\"status\":\"INGRESADO\","
                + "\"createdBy\":\"cliente@barriodigital.test\",\"futureField\":true}";
        var properties = new MessageProperties();
        properties.setContentType("application/json; charset=UTF-8");
        properties.setHeader("__TypeId__", "untrusted.ArbitraryClass");
        assertEquals(event, decoder.decode(new Message(json.getBytes(StandardCharsets.UTF_8), properties), Command.CERTIFICATE));
    }

    @ParameterizedTest
    @CsvSource({"'{broken'", "'null'", "'{}'", "'{} {}'"})
    void rejectsMalformedPayload(String json) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        assertThrows(InvalidCommandException.class,
                () -> decoder.decode(new Message(json.getBytes(StandardCharsets.UTF_8), properties), Command.CERTIFICATE));
    }

    @Test
    void rejectsMissingIdentityAndInvalidUuid() {
        var event = TestEvents.created();
        var invalid = new RequestEvent(event.type(), "../../escape", event.timestamp(), "trace", "corr",
                0L, "", event.status(), event.createdBy());
        assertThrows(InvalidCommandException.class, () -> decoder.decode(TestEvents.message(invalid), Command.CERTIFICATE));
    }

    @Test
    void rejectsOversizeAndNonJson() {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        assertThrows(InvalidCommandException.class,
                () -> decoder.decode(new Message(new byte[65537], properties), Command.CERTIFICATE));
        var message = TestEvents.message(TestEvents.created());
        message.getMessageProperties().setContentType("text/plain");
        assertThrows(InvalidCommandException.class, () -> decoder.decode(message, Command.CERTIFICATE));
    }

    @Test
    void emailRequiresRealRecipientButArtifactsDoNotInventOne() {
        var base = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.RESUELTO);
        var event = new RequestEvent(base.type(), base.eventId(), base.timestamp(), base.traceId(), base.correlationId(),
                base.requestId(), base.procedureTypeId(), base.status(), "entra-object-id");
        assertThrows(InvalidCommandException.class, () -> decoder.decode(TestEvents.message(event), Command.EMAIL));
        assertEquals(event, decoder.decode(TestEvents.message(event), Command.CERTIFICATE));
        assertThrows(InvalidCommandException.class, () -> EventDecoder.validateEmail("Name <user@example.test>"));
        assertThrows(InvalidCommandException.class, () -> EventDecoder.validateEmail("user@example.test\r\nBcc: bad@example.test"));
    }
}
