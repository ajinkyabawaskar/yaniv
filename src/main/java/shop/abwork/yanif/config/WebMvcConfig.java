package shop.abwork.yanif.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.CacheControl;

/**
 * Web MVC configuration for static resource cache control.
 * <p>
 * Uses a SINGLE resource handler to avoid conflicts when multiple handlers
 * point to the same location (classpath:/static/). Cache headers are applied
 * via a HandlerInterceptor (see StaticResourceCacheInterceptor).
 * </p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final StaticResourceCacheInterceptor cacheInterceptor;

    public WebMvcConfig(StaticResourceCacheInterceptor cacheInterceptor) {
        this.cacheInterceptor = cacheInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Single handler for all static resources - no cache by default
        // Cache headers added by StaticResourceCacheInterceptor based on path
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore().mustRevalidate());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(cacheInterceptor)
                .addPathPatterns("/**");
    }
}