package cl.barriodigital.notify;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import cl.barriodigital.notify.event.Command;
import cl.barriodigital.notify.event.RequestEvent;
import cl.barriodigital.notify.storage.CommandStore;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "NOTIFY_INTEGRATION_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotifyRabbitIT {
    @TempDir static Path directory;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    final String vhost = "notify-it-" + UUID.randomUUID();
    final String recipient = vhost + "@barriodigital.test";
    String username;
    String password;
    ConfigurableApplicationContext application;
    Connection connection;
    Channel channel;
    RequestEvent certificateEvent;
    boolean vhostCreated;

    @BeforeAll
    void start() throws Exception {
        assertNotNull(directory, "JUnit debe inicializar el directorio temporal antes de iniciar Notify.");
        username = env("RABBITMQ_USERNAME", "");
        password = env("RABBITMQ_PASSWORD", "");
        assertFalse(username.isBlank(), "Define RABBITMQ_USERNAME para la prueba real.");
        assertFalse(password.isBlank(), "Define RABBITMQ_PASSWORD para la prueba real.");
        try {
            vhostCreated = true;
            management("PUT", "/api/vhosts/" + vhost, Map.of());
            management("PUT", "/api/permissions/" + vhost + "/" + encode(username),
                    Map.of("configure", ".*", "write", ".*", "read", ".*"));
            var definitionsPath = Path.of(env("RABBITMQ_DEFINITIONS_FILE",
                    "../../infra-barriodigital/rabbitmq/definitions.json")).toAbsolutePath().normalize();
            var definitions = (ObjectNode) TestEvents.MAPPER.readTree(Files.readAllBytes(definitionsPath));
            definitions.putArray("vhosts").addObject().put("name", vhost);
            for (String section : new String[]{"exchanges", "queues", "bindings", "policies"}) {
                for (var entry : definitions.path(section)) {
                    ((ObjectNode) entry).put("vhost", vhost);
                }
            }
            management("POST", "/api/definitions", definitions);
            var factory = new ConnectionFactory();
            factory.setHost(env("RABBITMQ_HOST", "localhost"));
            factory.setPort(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
            factory.setUsername(username);
            factory.setPassword(password);
            factory.setVirtualHost(vhost);
            connection = factory.newConnection("notify-integration-publisher");
            channel = connection.createChannel();
            channel.confirmSelect();
            startApplication();
        } catch (Exception | AssertionError ex) {
            cleanup();
            throw ex;
        }
    }

    void startApplication() {
        application = new SpringApplicationBuilder(NotifyApplication.class).run(
                "--server.port=0", "--spring.rabbitmq.host=" + env("RABBITMQ_HOST", "localhost"),
                "--spring.rabbitmq.port=" + env("RABBITMQ_PORT", "5672"),
                "--spring.rabbitmq.virtual-host=" + vhost,
                "--barriodigital.notify.storage-directory=" + directory,
                "--barriodigital.notify.listeners-auto-startup=true",
                "--barriodigital.notify.max-attempts=3", "--barriodigital.notify.retry-delay-ms=50",
                "--spring.mail.host=" + env("SMTP_HOST", "localhost"),
                "--spring.mail.port=" + env("SMTP_PORT", "1025"),
                "--spring.mail.properties.mail.smtp.auth=false",
                "--spring.mail.properties.mail.smtp.starttls.enable=false",
                "--spring.mail.properties.mail.smtp.starttls.required=false",
                "--spring.mail.properties.mail.smtp.ssl.enable=false");
    }

    @AfterAll
    void cleanup() throws Exception {
        try {
            if (application != null) { application.close(); application = null; }
            if (connection != null && connection.isOpen()) { connection.close(); }
        } finally {
            if (vhostCreated) { management("DELETE", "/api/vhosts/" + vhost, null); vhostCreated = false; }
        }
    }

    @Test @Order(1)
    void deliversRealSmtpTicketPdfAndAcksDuplicates() throws Exception {
        certificateEvent = TestEvents.created();
        var email = withRecipient(TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.ADMITIDO));
        var crew = TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.EN_TERRENO);
        publish("cmd.direct", Command.CERTIFICATE.routingKey(), certificateEvent);
        publish("cmd.direct", Command.EMAIL.routingKey(), email);
        publish("cmd.topic", "crew.ticket.priority", crew);
        await(() -> completed(Command.CERTIFICATE, 1) && completed(Command.EMAIL, 1) && completed(Command.CREW, 1));
        var store = application.getBean(CommandStore.class);
        var pdf = store.directory("certificates").resolve(store.key(certificateEvent, Command.CERTIFICATE) + ".pdf");
        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertTrue(new PDFTextStripper().getText(document).contains(certificateEvent.eventId()));
        }
        var ticket = TestEvents.MAPPER.readTree(Files.readAllBytes(
                store.directory("tickets").resolve(store.key(crew, Command.CREW) + ".json")));
        assertEquals(crew.eventId(), ticket.path("eventId").asString());
        assertEquals("EN_TERRENO", ticket.path("status").asString());
        await(() -> mailCount() == 1);
        publish("cmd.direct", Command.CERTIFICATE.routingKey(), certificateEvent);
        publish("cmd.direct", Command.EMAIL.routingKey(), email);
        publish("cmd.direct", Command.CREW.routingKey(), crew);
        await(() -> duplicate(Command.CERTIFICATE) && duplicate(Command.EMAIL) && duplicate(Command.CREW));
        assertEquals(1, mailCount());
        for (var command : Command.values()) {
            await(() -> queueEmpty(command.queue()));
        }
        // RESUELTO uses the same eventId in two independent commands.
        var resolved = withRecipient(TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.RESUELTO));
        publish("cmd.direct", Command.EMAIL.routingKey(), resolved);
        publish("cmd.direct", Command.CERTIFICATE.routingKey(), resolved);
        await(() -> completed(Command.EMAIL, 2) && completed(Command.CERTIFICATE, 2));
        await(() -> mailCount() == 2);
        System.out.println("REAL ACK: SMTP recibido, ticket JSON y PDF verificables; duplicados sin repetir acciones.");
    }

    @Test @Order(2)
    void invalidPayloadNacksIntoAllExistingDlqs() throws Exception {
        for (var command : Command.values()) {
            publishBytes(command.routingKey(), "{}".getBytes(StandardCharsets.UTF_8));
            await(() -> readyCount(command.queue() + ".dlq") == 1);
            var rejected = channel.basicGet(command.queue() + ".dlq", true);
            assertNotNull(rejected);
            var deaths = (java.util.List<?>) rejected.getProps().getHeaders().get("x-death");
            assertEquals("rejected", ((Map<?, ?>) deaths.getFirst()).get("reason").toString());
        }
        System.out.println("REAL NACK: payload invalido -> basicNack(requeue=false) -> tres DLQ, x-death reason=rejected.");
    }

    @Test @Order(3)
    void unavailableSmtpRetriesOnlyThreeTimesThenDlq() throws Exception {
        var sender = application.getBean(JavaMailSenderImpl.class);
        int originalPort = sender.getPort();
        int closedPort;
        try (var socket = new java.net.ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        var event = withRecipient(TestEvents.event(RequestEvent.Type.REQUEST_STATUS_CHANGED, RequestEvent.Status.ADMITIDO));
        try {
            sender.setPort(closedPort);
            publish("cmd.direct", Command.EMAIL.routingKey(), event);
            await(() -> readyCount(Command.EMAIL.queue() + ".dlq") == 1);
            var store = application.getBean(CommandStore.class);
            var receipt = TestEvents.MAPPER.readValue(Files.readAllBytes(store.receiptPath(event, Command.EMAIL)), CommandStore.Receipt.class);
            assertEquals(3, receipt.attempts());
            assertFalse(receipt.completed());
            assertEquals(2, count("notify.command.retries", Command.EMAIL));
            publish("cmd.direct", Command.EMAIL.routingKey(), event);
            await(() -> readyCount(Command.EMAIL.queue() + ".dlq") == 2);
            assertEquals(3, TestEvents.MAPPER.readValue(Files.readAllBytes(store.receiptPath(event, Command.EMAIL)), CommandStore.Receipt.class).attempts());
            assertEquals(2, mailCount());
        } finally { sender.setPort(originalPort); }
        System.out.println("REAL RETRY: SMTP inaccesible, 3 intentos persistidos, NACK a DLQ; reentrega no reinicia el limite.");
    }

    @Test @Order(4)
    void restartRetainsIdempotencyAndHealthEndpointWorks() throws Exception {
        application.close();
        startApplication();
        publish("cmd.direct", Command.CERTIFICATE.routingKey(), certificateEvent);
        await(() -> duplicate(Command.CERTIFICATE));
        assertEquals(0, count("notify.command.completed", Command.CERTIFICATE));
        var port = application.getEnvironment().getProperty("local.server.port");
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("UP", TestEvents.MAPPER.readTree(response.body()).path("status").asString());
        System.out.println("REAL RESTART: idempotencia conservada y /actuator/health UP.");
    }

    RequestEvent withRecipient(RequestEvent base) {
        return new RequestEvent(base.type(), base.eventId(), base.timestamp(), base.traceId(), base.correlationId(),
                base.requestId(), base.procedureTypeId(), base.status(), recipient);
    }

    void publish(String exchange, String key, RequestEvent event) throws Exception {
        var properties = new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2)
                .messageId(event.eventId()).correlationId(event.correlationId()).timestamp(Date.from(event.timestamp()))
                .headers(Map.of("traceId", event.traceId())).build();
        channel.basicPublish(exchange, key, properties, TestEvents.MAPPER.writeValueAsBytes(event));
        channel.waitForConfirmsOrDie(5000);
    }

    void publishBytes(String key, byte[] bytes) throws Exception {
        channel.basicPublish("cmd.direct", key, new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(), bytes);
        channel.waitForConfirmsOrDie(5000);
    }

    double count(String name, Command command) {
        var counter = application.getBean(MeterRegistry.class).find(name).tag("command", command.routingKey()).counter();
        return counter == null ? 0 : counter.count();
    }
    boolean completed(Command command, double expected) { return count("notify.command.completed", command) == expected; }
    boolean duplicate(Command command) { return count("notify.command.duplicate", command) >= 1; }

    int readyCount(String queue) {
        try { return channel.queueDeclarePassive(queue).getMessageCount(); }
        catch (Exception ex) { throw new IllegalStateException("No se pudo consultar la cola de prueba.", ex); }
    }

    boolean queueEmpty(String queue) {
        try {
            var result = management("GET", "/api/queues/" + vhost + "/" + encode(queue), null);
            return result.path("messages").asInt(-1) == 0;
        } catch (Exception ex) { throw new IllegalStateException("No se pudo consultar la cola de prueba.", ex); }
    }

    int mailCount() {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(env("NOTIFY_TEST_MAILPIT_URL", "http://localhost:8025")
                            + "/api/v1/search?query=" + encode("to:" + recipient)))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "Mailpit no esta disponible.");
            return TestEvents.MAPPER.readTree(response.body()).path("messages_count").asInt(-1);
        } catch (Exception ex) { throw new IllegalStateException("No se pudo consultar Mailpit.", ex); }
    }

    JsonNode management(String method, String path, Object body) throws Exception {
        var credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(env("RABBITMQ_MANAGEMENT_URL", "http://localhost:15672") + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(TestEvents.MAPPER.writeValueAsBytes(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (method.equals("DELETE") && response.statusCode() == 404) {
            return TestEvents.MAPPER.nullNode();
        }
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "RabbitMQ Management respondio HTTP " + response.statusCode() + " para " + path);
        return response.body().isBlank() ? TestEvents.MAPPER.nullNode() : TestEvents.MAPPER.readTree(response.body());
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) { return; }
            Thread.sleep(100);
        }
        fail("La condicion no se cumplio en 30 segundos.");
    }
    static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
