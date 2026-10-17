package cl.barriodigital.notify.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

class NotifyRabbitTopologyConfigTest {

    @Test
    void topologyIsDurableButNeverDeclaredByNotifyAndCannotOverrideDlxPolicies() {
        var topology = new NotifyRabbitTopologyConfig().notifyCommandTopology();
        assertThat(topology.getDeclarables()).hasSize(18).allSatisfy(declarable ->
                assertThat(declarable.shouldDeclare()).isFalse());
        assertThat(topology.getDeclarablesByType(Exchange.class)).hasSize(3).allSatisfy(exchange -> {
            assertThat(exchange.isDurable()).isTrue();
            assertThat(exchange.isAutoDelete()).isFalse();
        });
        assertThat(topology.getDeclarablesByType(Queue.class)).hasSize(6).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.isExclusive()).isFalse();
            assertThat(queue.isAutoDelete()).isFalse();
            assertThat(queue.getArguments()).isEqualTo(Map.of("x-queue-type", "classic"));
        });
        assertThat(topology.getDeclarablesByType(Binding.class)).hasSize(9);
    }
    @Test
    void topologyPreservesExistingNamesTypesAndRoutes() {
        var topology = new NotifyRabbitTopologyConfig().notifyCommandTopology();
        assertThat(topology.getDeclarablesByType(Exchange.class))
                .extracting(Exchange::getName, Exchange::getType)
                .containsExactlyInAnyOrder(tuple("cmd.direct", "direct"), tuple("cmd.topic", "topic"),
                        tuple("cmd.dead.dlx", "direct"));
        assertThat(topology.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .containsExactlyInAnyOrder("q.cmd.email", "q.cmd.crew", "q.cmd.certificate",
                        "q.cmd.email.dlq", "q.cmd.crew.dlq", "q.cmd.certificate.dlq");
        assertThat(topology.getDeclarablesByType(Binding.class))
                .extracting(Binding::getDestination, Binding::getExchange, Binding::getRoutingKey)
                .containsExactlyInAnyOrder(
                        tuple("q.cmd.email", "cmd.direct", "email.send"),
                        tuple("q.cmd.crew", "cmd.direct", "crew.ticket"),
                        tuple("q.cmd.certificate", "cmd.direct", "certificate.gen"),
                        tuple("q.cmd.email", "cmd.topic", "email.*"),
                        tuple("q.cmd.crew", "cmd.topic", "crew.#"),
                        tuple("q.cmd.certificate", "cmd.topic", "certificate.*"),
                        tuple("q.cmd.email.dlq", "cmd.dead.dlx", "q.cmd.email.dlq"),
                        tuple("q.cmd.crew.dlq", "cmd.dead.dlx", "q.cmd.crew.dlq"),
                        tuple("q.cmd.certificate.dlq", "cmd.dead.dlx", "q.cmd.certificate.dlq"));
        assertThat(topology.getDeclarablesByType(Binding.class)).allSatisfy(binding -> {
            assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
            assertThat(binding.getArguments()).isEmpty();
        });
    }

    @Test
    void listenersAndCommandsUseTheExistingQueuesAndRoutingKeys() throws Exception {
        var commands = cl.barriodigital.notify.event.Command.values();
        assertThat(commands).extracting(cl.barriodigital.notify.event.Command::queue,
                cl.barriodigital.notify.event.Command::routingKey)
                .containsExactly(tuple("q.cmd.email", "email.send"), tuple("q.cmd.crew", "crew.ticket"),
                        tuple("q.cmd.certificate", "certificate.gen"));
        for (var command : commands) {
            var listener = cl.barriodigital.notify.messaging.CommandListeners.class
                    .getMethod(command.name().toLowerCase(java.util.Locale.ROOT),
                            org.springframework.amqp.core.Message.class, com.rabbitmq.client.Channel.class)
                    .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
            assertThat(listener.queues()).containsExactly(command.queue());
            assertThat(listener.containerFactory()).isEqualTo("notifyListenerFactory");
            assertThat(listener.bindings()).isEmpty();
            assertThat(listener.queuesToDeclare()).isEmpty();
        }
    }

}
