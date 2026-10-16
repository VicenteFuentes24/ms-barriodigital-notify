package cl.barriodigital.notify.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.RequestEvent;
import cl.barriodigital.notify.storage.AtomicFiles;
import cl.barriodigital.notify.storage.CommandStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class CertificateService {
    private final CommandStore store;

    public CertificateService(CommandStore store) {
        this.store = store;
    }

    public void generate(RequestEvent event) throws IOException {
        var path = store.directory("certificates").resolve(store.key(event, Command.CERTIFICATE) + ".pdf");
        if (Files.exists(path)) {
            try (var existing = Loader.loadPDF(path.toFile())) {
                if (!new PDFTextStripper().getText(existing).contains(event.eventId())) {
                    throw new IOException("El PDF existente no coincide con el evento.");
                }
            }
            return;
        }
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            document.getDocumentInformation().setTitle("BarrioDigital - Constancia de tramite");
            PDType0Font font;
            try (var stream = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                if (stream == null) { throw new IOException("Fuente PDF no disponible."); }
                font = PDType0Font.load(document, stream);
            }
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 18);
                content.newLineAtOffset(50, 785);
                content.showText("BarrioDigital");
                content.setFont(font, 11);
                content.setLeading(18);
                var lines = List.of("Constancia de " + (event.status() == RequestEvent.Status.INGRESADO ? "ingreso" : "resolucion"),
                        "", "Tramite: " + event.requestId(), "Procedimiento: " + event.procedureTypeId(),
                        "Estado: " + event.status(), "Fecha del evento: " + event.timestamp(),
                        "Evento: " + event.eventId(), "Trace: " + event.traceId(),
                        "Correlacion: " + event.correlationId(), "",
                        "Constancia academica generada desde un evento de Requests.",
                        "No constituye un certificado firmado ni acredita validez legal.");
                for (var line : lines) {
                    for (var wrapped : wrap(supportedText(line, font), font, 495)) {
                        content.newLine();
                        content.showText(wrapped);
                    }
                }
                content.endText();
            }
            document.save(output);
            AtomicFiles.write(path, output.toByteArray());
        }
    }

    private String supportedText(String value, PDFont font) throws IOException {
        var result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            var character = new String(Character.toChars(codePoint));
            try {
                font.encode(character);
                result.append(character);
            } catch (IllegalArgumentException ex) {
                result.append('?');
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private List<String> wrap(String value, PDFont font, float width) throws IOException {
        var lines = new ArrayList<String>();
        if (value.isEmpty()) {
            return List.of("");
        }
        var line = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            var next = line.toString() + value.charAt(i);
            if (!line.isEmpty() && font.getStringWidth(next) * 11 / 1000 > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(value.charAt(i));
        }
        lines.add(line.toString());
        return lines;
    }
}
