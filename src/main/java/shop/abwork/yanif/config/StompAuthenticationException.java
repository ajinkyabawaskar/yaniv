package shop.abwork.yanif.config;

import org.springframework.messaging.MessagingException;

/**
 * A STOMP CONNECT refused because of who is asking, not what they asked for.
 *
 * <p>Carried as a type rather than a message string so {@link StompAuthErrorHandler} can
 * recognise it without matching on English prose, and so the client is told the difference
 * between "your session is stale, get a new one" and "the server broke". Those need opposite
 * responses: the first must stop retrying and re-authenticate, the second should keep retrying.
 */
public class StompAuthenticationException extends MessagingException {

    private final String reason;

    public StompAuthenticationException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Stable, machine-readable code sent to the client. Never localised, never reworded. */
    public String getReason() {
        return reason;
    }
}
