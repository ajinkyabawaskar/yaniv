package shop.abwork.yanif.controller;

import java.util.Locale;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    // todo - implement proper service -> repository -> controller  instead of direct template in controller
    private final StringRedisTemplate redisTemplate;

    public PingController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        try {
            String response = redisTemplate.execute((RedisConnection connection) -> {
                String result = connection.ping();
                return result == null ? "" : new String(result);
            });
            return ResponseEntity.ok((response == null ? "" : response).toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            return ResponseEntity.status(503).body("redis unavailable");
        }
    }
}
