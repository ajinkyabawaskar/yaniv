package shop.abwork.yanif.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.http.CacheControl;

import java.io.IOException;

/**
 * Web MVC configuration for static resource cache control and SPA routing.
 * <p>
 * Uses a SINGLE resource handler to avoid conflicts when multiple handlers
 * point to the same location (classpath:/static/). Cache headers are applied
 * via a HandlerInterceptor (see StaticResourceCacheInterceptor).
 * </p>
 * <p>
 * SPA routing: For any path that doesn't match an existing static resource,
 * serve index.html so React Router can handle client-side routing.
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
        // Single handler for all static resources with SPA fallback to index.html
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore().mustRevalidate())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }
                        // SPA fallback: serve index.html for any non-existing path
                        return location.createRelative("index.html");
                    }
                });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(cacheInterceptor)
                .addPathPatterns("/**");
    }
}