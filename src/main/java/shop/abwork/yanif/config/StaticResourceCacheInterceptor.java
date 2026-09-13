package shop.abwork.yanif.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Single writer of Cache-Control headers for every response (static assets,
 * SPA entry points and API). The resource handler in {@link WebMvcConfig}
 * deliberately sets no cache headers of its own, so nothing here is
 * overwritten downstream.
 *
 * Three tiers, matching what browsers, Cloudflare (free plan, Origin Cache
 * Control on by default) and the frontend drift check rely on:
 *
 * - Immutable (1 year): content-hashed CRA bundles under /static/**, plus
 *   unhashed-but-stable media (card SVGs, sounds, images, fonts, favicons,
 *   manifests). The frontend pins the unhashed ones per release with a
 *   {@code ?v=<frontend-version>} query string, so same-URL staleness across
 *   deploys cannot happen.
 * - API/WebSocket ({@code /api/}, {@code /ws}, {@code /actuator}): no-store.
 *   Game state and identity responses must never sit in any cache.
 * - Everything else (/, /index.html and SPA routes like /home, /join/…):
 *   no-cache. The entry point revalidates on every load (cheap 304), which is
 *   what lets a new deploy reach clients even though the bundles are immutable.
 */
@Component
public class StaticResourceCacheInterceptor implements HandlerInterceptor {

    private static final Set<String> IMMUTABLE_PATH_PREFIXES = Set.of(
            "/static/",
            "/cards/",
            "/images/",
            "/fonts/",
            "/sounds/",
            "/favicon.ico",
            "/favicon.svg",
            "/manifest.json",
            "/asset-manifest.json"
    );

    private static final Set<String> IMMUTABLE_PATH_SUFFIXES = Set.of(
            ".mp3"
    );

    private static final Set<String> NO_STORE_PATH_PREFIXES = Set.of(
            "/api/",
            "/ws",
            "/actuator"
    );

    private static final String IMMUTABLE_CACHE_HEADER = "public, max-age=31536000, immutable";
    private static final String NO_STORE_HEADER = "no-store";
    private static final String NO_CACHE_HEADER = "no-cache";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();

        if (isImmutable(path)) {
            response.setHeader("Cache-Control", IMMUTABLE_CACHE_HEADER);
        } else if (isNoStore(path)) {
            response.setHeader("Cache-Control", NO_STORE_HEADER);
        } else {
            response.setHeader("Cache-Control", NO_CACHE_HEADER);
        }

        return true;
    }

    private boolean isImmutable(String path) {
        return IMMUTABLE_PATH_PREFIXES.stream()
                .anyMatch(prefix -> path.startsWith(prefix) || path.equals(prefix))
                || IMMUTABLE_PATH_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    private boolean isNoStore(String path) {
        return NO_STORE_PATH_PREFIXES.stream()
                .anyMatch(prefix -> path.startsWith(prefix) || path.equals(prefix));
    }
}
