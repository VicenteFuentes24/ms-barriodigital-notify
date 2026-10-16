package cl.barriodigital.notify.service;

import java.nio.charset.StandardCharsets;
import cl.barriodigital.notify.config.NotifyProperties;
import cl.barriodigital.notify.event.EventDecoder;
import cl.barriodigital.notify.event.RequestEvent;
import jakarta.mail.MessagingException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private final JavaMailSender sender;
    private final NotifyProperties properties;

    public EmailService(JavaMailSender sender, NotifyProperties properties) {
        EventDecoder.validateEmail(properties.mailFrom());
        this.sender = sender;
        this.properties = properties;
    }

    public void send(RequestEvent event) throws MessagingException {
        EventDecoder.validateEmail(event.createdBy());
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.mailFrom());
        helper.setTo(event.createdBy());
        helper.setSubject("BarrioDigital - Tramite " + event.requestId() + " " + event.status());
        helper.setText("Su tramite " + event.requestId() + " se encuentra en estado " + event.status()
                + ".\nProcedimiento: " + event.procedureTypeId() + "\nEvento: " + event.eventId()
                + "\nFecha del evento: " + event.timestamp(), false);
        message.setHeader("Message-ID", "<" + event.eventId() + ".email.send@barriodigital.test>");
        message.setHeader("X-BarrioDigital-Event-Id", event.eventId());
        sender.send(message);
    }
}
