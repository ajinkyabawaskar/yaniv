package shop.abwork.yanif.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.repository.GamePlayerRepository;
import shop.abwork.yanif.repository.GameRepository;
import shop.abwork.yanif.repository.UserRepository;
import shop.abwork.yanif.security.JwtProvider;
import shop.abwork.yanif.service.GameService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level coverage for emotes: an emote sent exactly the way the browser sends it
 * (SEND with no content-type header, like stompjs {@code publish}) must come back on
 * the room topic. Catches routing/deserialization drops that unit tests cannot see,
 * because those call the controller method directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReactionWireIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private GameService gameService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private GamePlayerRepository gamePlayerRepository;
    @Autowired
    private JwtProvider jwtProvider;

    private String player1Id;
    private String player2Id;
    private String gameId;

    @BeforeEach
    void setUp() {
        gamePlayerRepository.deleteAll();
        gameRepository.deleteAll();
        userRepository.deleteAll();

        User u1 = userRepository.save(new User("fp1", "Player One", "AAA11111"));
        User u2 = userRepository.save(new User("fp2", "Player Two", "BBB22222"));
        player1Id = u1.getId();
        player2Id = u2.getId();

        Game game = gameService.createGame("ROOM1", 200, player1Id, 6);
        gameId = game.getId();
        gameService.addPlayerToGame(gameId, player1Id);
        gameService.addPlayerToGame(gameId, player2Id);
    }

    @Test
    @DisplayName("browser-style SEND reaches the room topic as a broadcast")
    void browserStyleSendIsBroadcast() throws Exception {
        String token = jwtProvider.generateToken(player1Id, "fp1");
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(
                        "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer " + token + "\n\n\0"));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
                frames.add(message.getPayload().toString());
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, null, URI.create("ws://localhost:" + port + "/ws/websocket"))
                .get(10, TimeUnit.SECONDS);

        // Wait for CONNECTED.
        String connected = null;
        for (int i = 0; i < 100 && connected == null; i++) {
            String f = frames.poll(100, TimeUnit.MILLISECONDS);
            if (f != null && f.startsWith("CONNECTED")) {
                connected = f;
            }
        }
        assertThat(connected).as("expected CONNECTED, got queue dump %s", frames).isNotNull();

        session.sendMessage(new TextMessage(
                "SUBSCRIBE\nid:sub-0\ndestination:/topic/room/" + gameId + "/reactions\n\n\0"));
        Thread.sleep(500);

        // Exactly what the browser publishes: JSON body, no content-type header.
        String body = "{\"type\":\"TAUNT\",\"targetUserId\":\"" + player1Id + "\"}";
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        session.sendMessage(new TextMessage(
                "SEND\ndestination:/app/room/" + gameId + "/reaction\ncontent-length:" + raw.length + "\n\n" + body + "\0"));

        String hit = null;
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && hit == null) {
            String f = frames.poll(500, TimeUnit.MILLISECONDS);
            if (f != null && f.contains("\"type\":\"TAUNT\"")) {
                hit = f;
            }
        }
        session.close();
        assertThat(hit).as("expected a TAUNT broadcast on the room topic").isNotNull();
    }
}
