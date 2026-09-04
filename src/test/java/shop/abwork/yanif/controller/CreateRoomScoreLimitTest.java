package shop.abwork.yanif.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.UserService;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A room may only be created at a limit the product actually offers.
 *
 * The score limit is a player-visible choice with a fixed set of values, so the API is
 * not the place to smuggle in a 1-point table where everyone is out after one round.
 */
class CreateRoomScoreLimitTest {

    private static final String HOST = "host-user";

    private GameService gameService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        UserService userService = mock(UserService.class);

        when(userService.getUserById(HOST)).thenReturn(Optional.of(new shop.abwork.yanif.entity.User(
                "friend-code", "Host", "AAAAAA")));
        when(gameService.getGameByRoomCode(anyString())).thenReturn(null);
        when(gameService.createGame(anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    Game game = new Game(inv.getArgument(0), inv.getArgument(1),
                            inv.getArgument(2), inv.getArgument(3));
                    game.setId("game-1");
                    return game;
                });
        when(gameService.addPlayerToGame(anyString(), anyString()))
                .thenReturn(new GamePlayer("game-1", HOST));

        mockMvc = MockMvcBuilders.standaloneSetup(new RoomController(gameService, userService)).build();
    }

    private org.springframework.test.web.servlet.ResultActions createWith(String body) throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken(HOST, null);
        return mockMvc.perform(post("/api/v1/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .principal(auth));
    }

    @Test
    @DisplayName("A room can be created at each offered limit")
    void offeredLimitsAreAccepted() throws Exception {
        createWith("{\"targetScore\":100}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetScore").value(100));
        createWith("{\"targetScore\":200}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetScore").value(200));
    }

    @Test
    @DisplayName("Omitting the limit still creates a 100-point table")
    void omittingTheLimitDefaultsTo100() throws Exception {
        createWith("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetScore").value(100));
    }

    @Test
    @DisplayName("A limit the product does not offer is refused")
    void unsupportedLimitsAreRefused() throws Exception {
        createWith("{\"targetScore\":1}").andExpect(status().isBadRequest());
        createWith("{\"targetScore\":150}").andExpect(status().isBadRequest());
        createWith("{\"targetScore\":1000000}").andExpect(status().isBadRequest());
        createWith("{\"targetScore\":0}").andExpect(status().isBadRequest());
        createWith("{\"targetScore\":-100}").andExpect(status().isBadRequest());

        verify(gameService, never()).createGame(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("The refusal names the limits that would have worked")
    void theRefusalSaysWhatIsAllowed() throws Exception {
        createWith("{\"targetScore\":150}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("100")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("200")));
    }
}
