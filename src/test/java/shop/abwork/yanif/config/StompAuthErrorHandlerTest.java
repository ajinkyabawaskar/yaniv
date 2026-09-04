package shop.abwork.yanif.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client stops retrying a refused CONNECT only if it can tell an auth refusal from a
 * server fault, and the only thing carrying that distinction is this header. If it stops
 * arriving nothing throws -- the browser just retries every 3s forever, which is the bug
 * this exists to prevent coming back.
 */
class StompAuthErrorHandlerTest {

    private final StompAuthErrorHandler handler = new StompAuthErrorHandler();

    private static Message<byte[]> clientMessage() {
        return MessageBuilder.createMessage(new byte[0],
                StompHeaderAccessor.create(org.springframework.messaging.simp.stomp.StompCommand.CONNECT)
                        .getMessageHeaders());
    }

    private static String authHeaderOf(Message<byte[]> error) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(error);
        return accessor.getFirstNativeHeader(StompAuthErrorHandler.AUTH_ERROR_HEADER);
    }

    @Test
    @DisplayName("an invalid token is reported with a machine-readable reason")
    void invalidTokenCarriesTheReason() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                clientMessage(),
                new StompAuthenticationException("invalid-token", "CONNECT carried an invalid token"));

        assertThat(authHeaderOf(error)).isEqualTo("invalid-token");
    }

    @Test
    @DisplayName("the reason survives the wrapping Spring applies to an interceptor's exception")
    void theReasonSurvivesWrapping() {
        // An exception thrown from a ChannelInterceptor does not reach the handler as itself.
        // This is the case the naive `ex instanceof` check gets wrong, and it is the only case
        // that actually happens in production.
        Throwable wrapped = new MessageDeliveryException(
                clientMessage(),
                "failed to send",
                new StompAuthenticationException("invalid-token", "CONNECT carried an invalid token"));

        assertThat(authHeaderOf(handler.handleClientMessageProcessingError(clientMessage(), wrapped)))
                .isEqualTo("invalid-token");
    }

    @Test
    @DisplayName("a missing token is distinguished from an invalid one")
    void missingTokenHasItsOwnReason() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                clientMessage(),
                new StompAuthenticationException("missing-token", "CONNECT requires a Bearer token"));

        assertThat(authHeaderOf(error)).isEqualTo("missing-token");
    }

    @Test
    @DisplayName("a server fault is NOT marked as auth, so the client keeps retrying")
    void aServerFaultIsNotAnAuthFailure() {
        // Marking this would log a player out mid-game because the broker hiccuped.
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                clientMessage(), new IllegalStateException("broker unavailable"));

        assertThat(authHeaderOf(error)).isNull();
    }

    @Test
    @DisplayName("a self-referential cause chain terminates")
    void aCyclicCauseChainDoesNotHang() {
        Exception looping = new IllegalStateException("boom") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(authHeaderOf(handler.handleClientMessageProcessingError(clientMessage(), looping)))
                .isNull();
    }
}
