package shop.abwork.yanif.presence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Presence is a plain module — no Spring annotations on it — so it is assembled here.
 * The clock is a constructor argument rather than a call to {@code Instant.now()} so an
 * absence can be dated in a test without sleeping.
 */
@Configuration
public class PresenceConfig {

    @Bean
    public Clock presenceClock() {
        return Clock.systemUTC();
    }

    @Bean
    public Presence presence(Clock presenceClock) {
        return new Presence(presenceClock);
    }
}
