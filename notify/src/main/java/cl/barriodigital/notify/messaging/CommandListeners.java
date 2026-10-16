package cl.barriodigital.notify.messaging;

import java.io.IOException;
import cl.barriodigital.notify.event.Command;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CommandListeners {
    private final CommandProcessor processor;

    public CommandListeners(CommandProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(id = "notify-email", queues = "q.cmd.email", containerFactory = "notifyListenerFactory")
    public void email(Message message, Channel channel) throws IOException {
        processor.process(Command.EMAIL, message, channel);
    }

    @RabbitListener(id = "notify-crew", queues = "q.cmd.crew", containerFactory = "notifyListenerFactory")
    public void crew(Message message, Channel channel) throws IOException {
        processor.process(Command.CREW, message, channel);
    }

    @RabbitListener(id = "notify-certificate", queues = "q.cmd.certificate", containerFactory = "notifyListenerFactory")
    public void certificate(Message message, Channel channel) throws IOException {
        processor.process(Command.CERTIFICATE, message, channel);
    }
}
