package shop.abwork.yanif.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Tells the client *why* a CONNECT was refused, in a header it can branch on.
 *
 * <p>Without this the only signal on the wire is the exception's {@code message} header, so a
 * client wanting to distinguish a stale token from a server fault has to string-match English
 * prose written in another language's source file. Nothing pins those two spellings together,
 * so the day someone rewords the exception the client silently stops recovering.
 *
 * <p>Why it matters that the client can tell: stompjs retries a failed CONNECT forever on
 * {@code reconnectDelay}. A retry is right for a server that is down and wrong for a token that
 * is expired — that one will be refused identically until something re-authenticates, which no
 * amount of retrying will do. The loop is invisible to the user; the tab just never connects.
 */
public class StompAuthErrorHandler extends StompSubProtocolErrorHandler {

    /**
     * Native ERROR-frame header naming the refusal. Read by {@code StompContext.tsx}; changing
     * the spelling on either side breaks recovery silently, so it is asserted from both.
     */
    public static final String AUTH_ERROR_HEADER = "x-auth-error";

    /** Chain depth to search for the cause. Bounded so a self-referential cause cannot hang. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage,
                                                              Throwable ex) {
        StompAuthenticationException failure = findAuthFailure(ex);
        if (failure == null) {
            return super.handleClientMessageProcessingError(clientMessage, ex);
        }

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(failure.getMessage());
        accessor.setNativeHeader(AUTH_ERROR_HEADER, failure.getReason());
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(EMPTY_PAYLOAD, accessor.getMessageHeaders());
    }

    /**
     * Spring wraps an interceptor's exception before it reaches here, so the thrown type is not
     * the type that was raised — the chain has to be walked.
     */
    private static StompAuthenticationException findAuthFailure(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof StompAuthenticationException failure) {
                return failure;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
