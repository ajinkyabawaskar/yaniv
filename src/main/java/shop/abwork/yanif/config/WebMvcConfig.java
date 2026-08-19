package shop.abwork.yanif.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.CacheControl;

import java.util.concurrent.TimeUnit;

/**
 * Web MVC configuration for static resource cache control.
 * <p>
 * - Immutable assets (images, fonts, SVGs, favicon, manifest): long-term cache (1 year)
 *   These are versioned via build hash or don't change between deployments.
 *   Preloading depends on browser cache working.
 * - HTML/JS/CSS: no cache (must revalidate) to prevent stale deployments.
 *   These change frequently and need fresh versions.
 * </p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final CacheControl IMMUTABLE_CACHE = CacheControl.maxAge(365, TimeUnit.DAYS)
            .cachePublic()
            .immutable();

    private static final CacheControl NO_CACHE = CacheControl.noStore().mustRevalidate();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Favicon - exact match, long-term cache
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(IMMUTABLE_CACHE);

        // Manifest - exact match, long-term cache
        registry.addResourceHandler("/manifest.json")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(IMMUTABLE_CACHE);

        // Card images and other immutable assets - long-term cache
        registry.addResourceHandler("/cards/**", "/images/**", "/fonts/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(IMMUTABLE_CACHE);

        // HTML, JS, CSS, and all other static resources - no cache to ensure fresh deployments
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(NO_CACHE);
    }
}