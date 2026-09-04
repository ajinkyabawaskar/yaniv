package shop.abwork.yanif.config;

import shop.abwork.yanif.security.JwtProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Collections;

/**
 * WebSocket Configuration for STOMP messaging.
 * Enables real-time communication between clients and server.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtProvider jwtProvider;

    public WebSocketConfig(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable a simple message broker that routes messages with these prefixes
        config.enableSimpleBroker("/queue", "/topic")
              // 10s, not 25s: this is detection latency, and it stacks on top of the
              // absence grace before a table waits on a player who has gone.
              .setHeartbeatValue(new long[] {10000, 10000})
              .setTaskScheduler(heartbeatScheduler());

        // Configure application destination prefix for client-to-server messages
        config.setApplicationDestinationPrefixes("/app");

        // Configure user-specific destination prefix for server-to-client messages
        config.setUserDestinationPrefix("/user");
    }

    @Bean
    public TaskScheduler heartbeatScheduler() {
        return new ThreadPoolTaskScheduler();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                        message, StompHeaderAccessor.class);

                if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                    return message;
                }

                // Reject rather than pass through: an unauthenticated CONNECT used to be
                // accepted, and every downstream handler then NPE'd on auth.getName().
                String authorization = accessor.getFirstNativeHeader("Authorization");
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    throw new StompAuthenticationException(
                            "missing-token", "CONNECT requires a Bearer token");
                }

                String token = authorization.substring(7);
                if (!jwtProvider.validateToken(token)) {
                    // Expired, wrong signing secret, malformed -- validateToken collapses all of
                    // them, and the client's response is the same either way: re-authenticate.
                    throw new StompAuthenticationException(
                            "invalid-token", "CONNECT carried an invalid token");
                }

                String userId = jwtProvider.extractUserId(token);
                accessor.setUser(new UsernamePasswordAuthenticationToken(
                        userId, null, Collections.emptyList()));

                return message;
            }
        });
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Register WebSocket endpoint with SockJS fallback
        // Allow all origins for network access (in production, restrict to specific domains)
        registry.setErrorHandler(new StompAuthErrorHandler());

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
