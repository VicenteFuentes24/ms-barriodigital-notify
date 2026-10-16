package cl.barriodigital.notify.event;

import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RequestEvent(
        @NotNull Type type,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") String eventId,
        @NotNull Instant timestamp,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String traceId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String correlationId,
        @NotNull @Positive Long requestId,
        @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}]+") String procedureTypeId,
        @NotNull Status status,
        @Size(max = 180) @Pattern(regexp = "[^\\p{Cntrl}]*") String createdBy) {
    public enum Type { REQUEST_CREATED, REQUEST_STATUS_CHANGED }
    public enum Status { INGRESADO, ADMITIDO, EN_GESTION, EN_TERRENO, RESUELTO, RECHAZADO }
}
