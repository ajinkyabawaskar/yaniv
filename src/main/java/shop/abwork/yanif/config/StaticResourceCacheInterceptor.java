package shop.abwork.yanif.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Interceptor to add appropriate Cache-Control headers for static resources.
 * <p>
 * - Immutable assets (cards, images, fonts, favicon, manifest): 1 year cache, immutable
 * - HTML/JS/CSS: no cache (must revalidate)
 * </p>
 * This avoids the Spring Boot resource handler conflict when multiple handlers
 * point to the same location.
 */
@Component
public class StaticResourceCacheInterceptor implements HandlerInterceptor {

    private static final Set<String> IMMUTABLE_PATH_PREFIXES = Set.of(
            "/cards/",
            "/images/",
            "/fonts/",
            "/favicon.ico",
            "/favicon.svg",
            "/manifest.json",
            "/asset-manifest.json"
    );

    private static final String IMMUTABLE_CACHE_HEADER = "public, max-age=31536000, immutable";
    private static final String NO_CACHE_HEADER = "no-store, must-revalidate";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();

        // Check if path matches immutable asset patterns
        boolean isImmutable = IMMUTABLE_PATH_PREFIXES.stream()
                .anyMatch(prefix -> path.startsWith(prefix) || path.equals(prefix));

        if (isImmutable) {
            response.setHeader("Cache-Control", IMMUTABLE_CACHE_HEADER);
        } else {
            response.setHeader("Cache-Control", NO_CACHE_HEADER);
        }

        return true;
    }
}