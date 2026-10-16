package cl.barriodigital.notify.service;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.RequestEvent;
import cl.barriodigital.notify.storage.AtomicFiles;
import cl.barriodigital.notify.storage.CommandStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CrewTicketService {
    public record CrewTicket(String ticketId, String eventId, Long requestId, String procedureTypeId,
                             String status, Instant timestamp, String traceId, String correlationId) { }
    private final CommandStore store;
    private final JsonMapper mapper;

    public CrewTicketService(CommandStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public void create(RequestEvent event) throws IOException {
        var ticket = new CrewTicket(store.key(event, Command.CREW), event.eventId(), event.requestId(),
                event.procedureTypeId(), event.status().name(), event.timestamp(), event.traceId(), event.correlationId());
        var path = store.directory("tickets").resolve(ticket.ticketId() + ".json");
        if (Files.exists(path)) {
            if (!ticket.equals(mapper.readValue(Files.readAllBytes(path), CrewTicket.class))) {
                throw new IOException("El ticket existente no coincide con el evento.");
            }
            return;
        }
        AtomicFiles.write(path, mapper.writeValueAsBytes(ticket));
    }
}
