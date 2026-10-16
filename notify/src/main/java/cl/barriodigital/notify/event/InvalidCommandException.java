package cl.barriodigital.notify.event;

public class InvalidCommandException extends RuntimeException {
    public InvalidCommandException() {
        super("El comando no contiene un evento valido o compatible.");
    }
}
