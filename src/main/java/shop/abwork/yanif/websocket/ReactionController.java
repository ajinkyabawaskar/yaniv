package shop.abwork.yanif.websocket;

import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.UserService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emotes: the one thing players say to each other during a round.
 *
 * Deliberately kept out of {@link GameStateController}. Nothing here touches the rules
 * engine, nothing is snapshotted to Redis, and losing an emote costs a player nothing --
 * so it must not share a lock, a mutation hook or a persistence path with the game.
 *
 * Broadcast on a room topic rather than per-player queues: unlike game state, every
 * player is told exactly the same thing, and an emote carries no hidden-hand information
 * that would need filtering per recipient.
 */
@Controller
public class ReactionController {

    /**
     * The words each emote carries. Written server-side so every client shows the same
     * thing, and so a client cannot put its own text on another player's screen -- the
     * request names a type, never a message.
     */
    private static final Map<String, String> REACTION_TEXT = Map.of(
            "LOVE", "thanks for the card(s)",
            "RAGE", "jaldi khel l***",
            "TAUNT", "halke ho jao",
            "MOCK", "lambe lag gaye",
            "SHOCK", "Bhaisaab, yeh kya tha?",
            "FLEX", "Mera toh dhandha chal raha hai"
    );

    private static final List<String> ALLOWED_TYPES = List.of("LOVE", "RAGE", "TAUNT", "MOCK", "SHOCK", "FLEX");

    /**
     * Minimum gap between one player's emotes. Every emote puts a line of text on
     * everyone's felt, so an unthrottled sender could paper the table for the room --
     * but the window has to stay short enough that a few taps a second still land,
     * or the buttons feel dead.
     */
    private static final long COOLDOWN_MS = 250;

    private final GameService gameService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    /** roomId:userId -> last accepted emote, for the cooldown. Cosmetic state, so in-memory. */
    private final ConcurrentHashMap<String, Long> lastReactionAt = new ConcurrentHashMap<>();

    public ReactionController(GameService gameService,
                              UserService userService,
                              SimpMessagingTemplate messagingTemplate) {
        this.gameService = gameService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Send an emote to everyone in the room.
     *
     * Request: {@code { "type": "LOVE" | "RAGE" | "TAUNT" | "MOCK" | "SHOCK" | "FLEX", "targetUserId": "usr_1" }}
     *
     * The target is the player the emote names -- someone else for LOVE, RAGE, MOCK,
     * SHOCK and FLEX, which read as "Ari -> Bob", and yourself for a TAUNT, which is
     * thrown at the whole table and so names no recipient. Both sender and target must
     * be players in this room, so an emote cannot be aimed at a seat that is not there.
     */
    @MessageMapping("/room/{roomId}/reaction")
    public void handleReaction(@DestinationVariable String roomId,
                               ReactionMessage request,
                               Authentication auth) {
        try {
            String fromUserId = auth.getName();

            String type = request.type == null ? "" : request.type.toUpperCase(Locale.ROOT);
            if (!ALLOWED_TYPES.contains(type)) {
                return;
            }

            List<GamePlayer> players = gameService.getGamePlayers(roomId);
            if (players == null || players.stream().noneMatch(p -> p.getId().getUserId().equals(fromUserId))) {
                return;
            }

            // Default to the sender's own seat: a TAUNT is thrown from where you sit.
            String targetUserId = request.targetUserId == null || request.targetUserId.isBlank()
                    ? fromUserId
                    : request.targetUserId;
            if (players.stream().noneMatch(p -> p.getId().getUserId().equals(targetUserId))) {
                return;
            }

            String cooldownKey = roomId + ":" + fromUserId;
            long now = System.currentTimeMillis();
            Long previous = lastReactionAt.get(cooldownKey);
            if (previous != null && now - previous < COOLDOWN_MS) {
                return;
            }
            lastReactionAt.put(cooldownKey, now);
            // The map only ever holds one entry per seat per room, but rooms end.
            lastReactionAt.entrySet().removeIf(e -> now - e.getValue() > 60_000);

            ReactionBroadcast broadcast = new ReactionBroadcast();
            broadcast.id = "rct_" + UUID.randomUUID();
            broadcast.type = type;
            broadcast.fromUserId = fromUserId;
            broadcast.fromDisplayName = userService.getUserById(fromUserId)
                    .map(u -> u.getDisplayName())
                    .orElse("A player");
            broadcast.targetUserId = targetUserId;
            broadcast.text = REACTION_TEXT.get(type);

            messagingTemplate.convertAndSend(reactionDestination(roomId), broadcast);
        } catch (Exception e) {
            // An emote that fails to send is not worth an error frame: the round carries on.
            System.err.println("Error broadcasting reaction: " + e.getMessage());
        }
    }

    static String reactionDestination(String roomId) {
        return "/topic/room/" + roomId + "/reactions";
    }

    /** Request DTO. */
    public static class ReactionMessage {
        public String type;
        public String targetUserId;
    }

    /** Broadcast DTO. */
    public static class ReactionBroadcast {
        public String id;
        public String type;
        public String fromUserId;
        public String fromDisplayName;
        public String targetUserId;
        /** The words shown on every screen. Server-authored; see REACTION_TEXT. */
        public String text;
    }
}
