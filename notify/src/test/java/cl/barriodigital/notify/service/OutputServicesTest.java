package cl.barriodigital.notify.service;

import java.nio.file.Files;
import java.nio.file.Path;
import cl.barriodigital.notify.TestEvents;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.RequestEvent;
import cl.barriodigital.notify.storage.CommandStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutputServicesTest {
    @TempDir Path directory;

    CommandStore store() throws Exception {
        return new CommandStore(TestEvents.properties(directory, 3), TestEvents.MAPPER, new SimpleMeterRegistry());
    }

    @Test
    void createsVerifiableTicketWithoutInventingCrewAssignment() throws Exception {
        var store = store();
        var service = new CrewTicketService(store, TestEvents.MAPPER);
        var event = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.EN_TERRENO);
        service.create(event);
        var path = store.directory("tickets").resolve(store.key(event, Command.CREW) + ".json");
        var before = Files.readAllBytes(path);
        var ticket = TestEvents.MAPPER.readValue(before, CrewTicketService.CrewTicket.class);
        assertEquals(event.eventId(), ticket.eventId());
        assertEquals(42L, ticket.requestId());
        assertEquals("EN_TERRENO", ticket.status());
        assertEquals(event.correlationId(), ticket.correlationId());
        service.create(event);
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test
    void generatesRealReadablePdfAndRenderForVisualVerification() throws Exception {
        var store = store();
        var service = new CertificateService(store);
        var event = TestEvents.created();
        service.generate(event);
        var path = store.directory("certificates").resolve(store.key(event, Command.CERTIFICATE) + ".pdf");
        var before = Files.readAllBytes(path);
        assertTrue(new String(before, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF"));
        try (var document = Loader.loadPDF(path.toFile())) {
            var text = new PDFTextStripper().getText(document);
            assertEquals(1, document.getNumberOfPages());
            assertTrue(text.contains("BarrioDigital"));
            assertTrue(text.contains("INGRESADO"));
            assertTrue(text.contains(event.eventId()));
            assertTrue(text.contains("No constituye un certificado firmado"));
            var verification = Path.of("target", "verification");
            Files.createDirectories(verification);
            Files.copy(path, verification.resolve("certificate.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            javax.imageio.ImageIO.write(new PDFRenderer(document).renderImageWithDPI(0, 100),
                    "png", verification.resolve("certificate.png").toFile());
        }
        service.generate(event);
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test
    void generatesResolutionAndHandlesLongUnicodeMetadata() throws Exception {
        var store = store();
        var base = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.RESUELTO);
        var event = new RequestEvent(base.type(), base.eventId(), base.timestamp(), "x".repeat(128), "y".repeat(128),
                base.requestId(), "\u00e1\u20ac\u4e2d".repeat(40), base.status(), null);
        new CertificateService(store).generate(event);
        try (var document = Loader.loadPDF(store.directory("certificates").resolve(store.key(event, Command.CERTIFICATE) + ".pdf").toFile())) {
            assertTrue(new PDFTextStripper().getText(document).contains("resolucion"));
        }
    }

    @Test
    void buildsRealMimeMailWithStableMessageId() throws Exception {
        var sender = mock(JavaMailSender.class);
        var message = new MimeMessage(Session.getInstance(new java.util.Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        var event = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.ADMITIDO);
        new EmailService(sender, TestEvents.properties(directory, 3)).send(event);
        verify(sender).send(message);
        assertEquals(event.createdBy(), message.getAllRecipients()[0].toString());
        assertTrue(message.getSubject().contains("ADMITIDO"));
        assertEquals("<" + event.eventId() + ".email.send@barriodigital.test>", message.getHeader("Message-ID", null));
        assertTrue(message.getContent().toString().contains(event.eventId()));
    }
}
