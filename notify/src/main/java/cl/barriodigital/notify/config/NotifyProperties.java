package cl.barriodigital.notify.config;

import java.nio.file.Path;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("barriodigital.notify")
public record NotifyProperties(
        @NotNull Path storageDirectory,
        @NotBlank String mailFrom,
        @Min(1) @Max(10) int maxAttempts,
        @Min(0) @Max(30000) long retryDelayMs,
        boolean listenersAutoStartup) {
}
