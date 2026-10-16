package cl.barriodigital.notify.event;

import java.util.Locale;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.Validator;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventDecoder {
    private static final int MAX_BODY_BYTES = 65536;
    private final JsonMapper mapper;
    private final Validator validator;

    public EventDecoder(JsonMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public RequestEvent decode(Message message, Command command) {
        try {
            var contentType = message.getMessageProperties().getContentType();
            if (message.getBody().length == 0 || message.getBody().length > MAX_BODY_BYTES
                    || contentType == null
                    || !contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT).equals("application/json")) {
                throw new InvalidCommandException();
            }
            var event = mapper.readValue(message.getBody(), RequestEvent.class);
            if (event == null || !validator.validate(event).isEmpty() || !compatible(event, command)) {
                throw new InvalidCommandException();
            }
            if (command == Command.EMAIL) {
                validateEmail(event.createdBy());
            }
            return event;
        } catch (RuntimeException ex) {
            // Never log the raw payload, recipient or deserialization exception.
            throw new InvalidCommandException();
        }
    }

    public static void validateEmail(String address) {
        try {
            if (address == null || address.isBlank() || !address.equals(address.strip())
                    || address.contains("\r") || address.contains("\n")) {
                throw new InvalidCommandException();
            }
            var parsed = new InternetAddress(address, true);
            parsed.validate();
            if (parsed.getPersonal() != null || !address.equals(parsed.getAddress())
                    || !address.contains("@")) {
                throw new InvalidCommandException();
            }
        } catch (AddressException ex) {
            throw new InvalidCommandException();
        }
    }

    private boolean compatible(RequestEvent event, Command command) {
        if (event.type() == RequestEvent.Type.REQUEST_CREATED) {
            return command == Command.CERTIFICATE && event.status() == RequestEvent.Status.INGRESADO;
        }
        return switch (command) {
            case EMAIL -> event.status() == RequestEvent.Status.ADMITIDO
                    || event.status() == RequestEvent.Status.RESUELTO;
            case CREW -> event.status() == RequestEvent.Status.EN_TERRENO;
            case CERTIFICATE -> event.status() == RequestEvent.Status.RESUELTO;
        };
    }
}
