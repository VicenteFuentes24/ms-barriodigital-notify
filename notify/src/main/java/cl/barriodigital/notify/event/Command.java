package cl.barriodigital.notify.event;

public enum Command {
    EMAIL("email.send", "q.cmd.email"),
    CREW("crew.ticket", "q.cmd.crew"),
    CERTIFICATE("certificate.gen", "q.cmd.certificate");

    private final String routingKey;
    private final String queue;

    Command(String routingKey, String queue) {
        this.routingKey = routingKey;
        this.queue = queue;
    }

    public String routingKey() { return routingKey; }
    public String queue() { return queue; }
}
