package shop.abwork.yanif.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.UserService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Emotes go to the whole room, so the checks that matter are the ones that stop a client
 * putting something on other people's screens: the emote must be one the game offers, it
 * must come from a player in the room, it must land on a seat that is in the room, and
 * the words are the server's, never the sender's.
 */
class ReactionControllerTest {

    private static final String ROOM = "room-emote";
    private static final String SENDER = "player-a";
    private static final String OTHER = "player-b";
    private static final String OUTSIDER = "player-z";

    private GameService gameService;
    private UserService userService;
    private SimpMessagingTemplate messagingTemplate;
    private ReactionController controller;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        userService = mock(UserService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        when(gameService.getGamePlayers(ROOM))
                .thenReturn(List.of(new GamePlayer(ROOM, SENDER), new GamePlayer(ROOM, OTHER)));
        User sender = new User("fp", "Alice", "AAA111");
        when(userService.getUserById(SENDER)).thenReturn(Optional.of(sender));
        when(userService.getUserById(anyString())).thenReturn(Optional.of(sender));

        controller = new ReactionController(gameService, userService, messagingTemplate);
    }

    private static Authentication authOf(String userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId);
        return auth;
    }

    private static ReactionController.ReactionMessage message(String type, String targetUserId) {
        ReactionController.ReactionMessage msg = new ReactionController.ReactionMessage();
        msg.type = type;
        msg.targetUserId = targetUserId;
        return msg;
    }

    private ReactionController.ReactionBroadcast captureBroadcast() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/room/" + ROOM + "/reactions"), payload.capture());
        return (ReactionController.ReactionBroadcast) payload.getValue();
    }

    @Test
    @DisplayName("A taunt carries the server's words, not the sender's")
    void tauntTextComesFromTheServer() {
        controller.handleReaction(ROOM, message("TAUNT", null), authOf(SENDER));

        ReactionController.ReactionBroadcast sent = captureBroadcast();
        assertEquals("TAUNT", sent.type);
        assertEquals("khali ho jao", sent.text);
        assertEquals(SENDER, sent.fromUserId);
        assertEquals(SENDER, sent.targetUserId, "a taunt is thrown from the seat you sit in");
        assertEquals("Alice", sent.fromDisplayName);
    }

    @Test
    @DisplayName("Love and rage land on the seat they were aimed at, and carry their own words")
    void aimedEmotesKeepTheirTarget() {
        controller.handleReaction(ROOM, message("love", OTHER), authOf(SENDER));

        ReactionController.ReactionBroadcast sent = captureBroadcast();
        assertEquals("LOVE", sent.type, "the type is normalised, so a lowercase client still works");
        assertEquals(OTHER, sent.targetUserId);
        assertEquals("thanks for the card(s)", sent.text);
    }

    @Test
    @DisplayName("Every emote the game offers carries server-authored words")
    void everyEmoteTypeCarriesText() {
        for (String type : List.of("LOVE", "RAGE", "TAUNT")) {
            // A fresh controller per type: the cooldown is per sender, and this sends
            // three emotes from one seat inside its window.
            reset(messagingTemplate);
            ReactionController fresh = new ReactionController(gameService, userService, messagingTemplate);
            fresh.handleReaction(ROOM, message(type, OTHER), authOf(SENDER));

            ReactionController.ReactionBroadcast sent = captureBroadcast();
            assertNotNull(sent.text, type + " must arrive with words on it");
            assertFalse(sent.text.isBlank(), type + " must arrive with words on it");
        }
    }

    @Test
    @DisplayName("An emote the game does not offer is dropped")
    void unknownTypesAreDropped() {
        controller.handleReaction(ROOM, message("NUKE", OTHER), authOf(SENDER));
        controller.handleReaction(ROOM, message(null, OTHER), authOf(SENDER));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Someone who is not in the room cannot emote into it")
    void outsidersCannotEmote() {
        controller.handleReaction(ROOM, message("RAGE", OTHER), authOf(OUTSIDER));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("An emote cannot be aimed at a seat that is not in the room")
    void targetMustBeAtTheTable() {
        controller.handleReaction(ROOM, message("LOVE", OUTSIDER), authOf(SENDER));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("One player cannot paper the table: emotes are rate limited per seat")
    void emotesAreRateLimited() {
        controller.handleReaction(ROOM, message("LOVE", OTHER), authOf(SENDER));
        controller.handleReaction(ROOM, message("LOVE", OTHER), authOf(SENDER));
        controller.handleReaction(ROOM, message("RAGE", OTHER), authOf(SENDER));

        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("The cooldown is per seat, so one player emoting does not mute another")
    void rateLimitDoesNotLeakBetweenPlayers() {
        controller.handleReaction(ROOM, message("LOVE", OTHER), authOf(SENDER));
        controller.handleReaction(ROOM, message("LOVE", SENDER), authOf(OTHER));

        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class));
    }
}
