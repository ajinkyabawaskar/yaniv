package shop.abwork.yanif.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.CacheControl;

/**
 * Web MVC configuration for static resource cache control.
 * <p>
 * All static resources: no cache (must revalidate on every request)
 * This prevents any caching issues where users see stale deployments.
 * </p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Disable caching for ALL static resources
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore().mustRevalidate());
    }
}