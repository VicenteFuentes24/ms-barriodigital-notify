package cl.barriodigital.notify.event;

import cl.barriodigital.notify.config.NotifyRabbitTopologyConfig;

public enum Command {
    EMAIL(NotifyRabbitTopologyConfig.EMAIL_KEY, NotifyRabbitTopologyConfig.EMAIL_QUEUE),
    CREW(NotifyRabbitTopologyConfig.CREW_KEY, NotifyRabbitTopologyConfig.CREW_QUEUE),
    CERTIFICATE(NotifyRabbitTopologyConfig.CERTIFICATE_KEY, NotifyRabbitTopologyConfig.CERTIFICATE_QUEUE);

    private final String routingKey;
    private final String queue;

    Command(String routingKey, String queue) {
        this.routingKey = routingKey;
        this.queue = queue;
    }

    public String routingKey() { return routingKey; }
    public String queue() { return queue; }
}
