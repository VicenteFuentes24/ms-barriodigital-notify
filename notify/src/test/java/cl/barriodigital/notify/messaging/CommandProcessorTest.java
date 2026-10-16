package cl.barriodigital.notify.messaging;

import java.io.IOException;
import java.nio.file.Path;
import cl.barriodigital.notify.TestEvents;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.EventDecoder;
import cl.barriodigital.notify.event.RequestEvent;
import cl.barriodigital.notify.service.CertificateService;
import cl.barriodigital.notify.service.CrewTicketService;
import cl.barriodigital.notify.service.EmailService;
import cl.barriodigital.notify.storage.CommandStore;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandProcessorTest {
    @TempDir Path directory;
    ValidatorFactory validator;
    CommandStore store;
    CommandProcessor processor;
    Channel channel;
    EmailService email;
    CrewTicketService crew;
    CertificateService certificate;

    @BeforeEach
    void setup() throws Exception {
        validator = Validation.buildDefaultValidatorFactory();
        var metrics = new SimpleMeterRegistry();
        store = new CommandStore(TestEvents.properties(directory, 3), TestEvents.MAPPER, metrics);
        email = mock(EmailService.class);
        crew = mock(CrewTicketService.class);
        certificate = mock(CertificateService.class);
        channel = mock(Channel.class);
        processor = new CommandProcessor(new EventDecoder(TestEvents.MAPPER, validator.getValidator()),
                store, email, crew, certificate, metrics);
    }

    @AfterEach void close() { validator.close(); }

    @Test
    void acksSuccessfulActionAndDuplicateWithoutRepeatingAction() throws Exception {
        var event = TestEvents.created();
        processor.process(Command.CERTIFICATE, TestEvents.message(event), channel);
        processor.process(Command.CERTIFICATE, TestEvents.message(event), channel);
        verify(certificate, times(1)).generate(event);
        verify(channel, times(2)).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void dispatchesEmailAndCrew() throws Exception {
        var admitted = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.ADMITIDO);
        var terrain = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.EN_TERRENO);
        processor.process(Command.EMAIL, TestEvents.message(admitted), channel);
        processor.process(Command.CREW, TestEvents.message(terrain), channel);
        verify(email).send(admitted);
        verify(crew).create(terrain);
        verify(channel, times(2)).basicAck(7, false);
    }

    @Test
    void invalidPayloadNacksWithoutRequeueOrSideEffect() throws Exception {
        processor.process(Command.EMAIL, TestEvents.message(TestEvents.created()), channel);
        verify(channel).basicNack(7, false, false);
        verifyNoInteractions(email, crew, certificate);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void failedActionRetriesThreeTimesThenNacksToDlq() throws Exception {
        var event = TestEvents.created();
        doThrow(new IOException("Injected failure")).when(certificate).generate(event);
        processor.process(Command.CERTIFICATE, TestEvents.message(event), channel);
        verify(certificate, times(3)).generate(event);
        verify(channel).basicNack(7, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void actionFailureThenRecoveryAcks() throws Exception {
        var event = TestEvents.created();
        doThrow(new IOException("Injected failure")).doNothing().when(certificate).generate(event);
        processor.process(Command.CERTIFICATE, TestEvents.message(event), channel);
        verify(certificate, times(2)).generate(event);
        verify(channel).basicAck(7, false);
    }

    @Test
    void lostAckDoesNotRepeatSideEffectOnRedelivery() throws Exception {
        var event = TestEvents.created();
        doThrow(new IOException("Lost ACK")).doNothing().when(channel).basicAck(7, false);
        assertThrows(IOException.class, () -> processor.process(Command.CERTIFICATE, TestEvents.message(event), channel));
        processor.process(Command.CERTIFICATE, TestEvents.message(event), channel);
        verify(certificate, times(1)).generate(event);
        verify(channel, times(2)).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void storageErrorDoesNotAck() throws Exception {
        var event = TestEvents.created();
        java.nio.file.Files.createDirectory(store.receiptPath(event, Command.CERTIFICATE));
        processor.process(Command.CERTIFICATE, TestEvents.message(event), channel);
        verify(channel).basicNack(7, false, false);
        verifyNoInteractions(certificate);
    }
}
