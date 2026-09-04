package shop.abwork.yanif.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end, over a real socket, reading the raw STOMP frame the browser would receive.
 *
 * <p>The unit test next door builds the exception wrapping by hand, which only proves the
 * unwrapping logic is right *if* the assumption about how Spring wraps it is right. This one
 * makes no assumption: it sends a CONNECT with a junk token and reads what comes back off
 * the wire. If {@code setErrorHandler} were not honoured, or the native header were stripped
 * somewhere in the SockJS layer, nothing else in the suite would notice -- the browser would
 * simply go back to retrying forever.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StompConnectRejectionIntegrationTest {

    @LocalServerPort
    private int port;

    /** The raw-websocket transport SockJS exposes; it carries STOMP frames unwrapped. */
    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private static String connectFrame(String authorizationHeader) {
        StringBuilder frame = new StringBuilder("CONNECT\naccept-version:1.2\nhost:localhost\n");
        if (authorizationHeader != null) {
            frame.append("Authorization:").append(authorizationHeader).append('\n');
        }
        return frame.append('\n').append('\0').toString();
    }

    private String firstFrameFor(String authorizationHeader) throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(connectFrame(authorizationHeader)));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
                received.complete(message.getPayload().toString());
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                received.completeExceptionally(exception);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                received.completeExceptionally(new IllegalStateException("closed before any frame"));
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        new StandardWebSocketClient().execute(handler, null, URI.create(wsUrl()))
                .get(10, TimeUnit.SECONDS);
        return received.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("an expired or unsignable token comes back as ERROR with x-auth-error")
    void anInvalidTokenIsMarkedAsAnAuthFailureOnTheWire() throws Exception {
        String frame = firstFrameFor("Bearer not-a-real-token");

        assertThat(frame).startsWith("ERROR");
        assertThat(frame).contains(StompAuthErrorHandler.AUTH_ERROR_HEADER + ":invalid-token");
    }

    @Test
    @DisplayName("a CONNECT with no token at all is marked too")
    void aMissingTokenIsMarkedOnTheWire() throws Exception {
        String frame = firstFrameFor(null);

        assertThat(frame).startsWith("ERROR");
        assertThat(frame).contains(StompAuthErrorHandler.AUTH_ERROR_HEADER + ":missing-token");
    }
}
