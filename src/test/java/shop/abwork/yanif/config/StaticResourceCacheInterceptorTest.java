package shop.abwork.yanif.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StaticResourceCacheInterceptorTest {

    private final StaticResourceCacheInterceptor interceptor = new StaticResourceCacheInterceptor();

    private String headerFor(String requestUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        return response.getHeader("Cache-Control");
    }

    @Test
    void hashedBundlesAreImmutable() throws Exception {
        assertEquals("public, max-age=31536000, immutable", headerFor("/static/js/main.93736aac.js"));
        assertEquals("public, max-age=31536000, immutable", headerFor("/static/css/main.4f3c1b.css"));
    }

    @Test
    void stableMediaIsImmutable() throws Exception {
        assertEquals("public, max-age=31536000, immutable", headerFor("/cards/ace_of_hearts.svg"));
        assertEquals("public, max-age=31536000, immutable", headerFor("/background.mp3"));
        assertEquals("public, max-age=31536000, immutable", headerFor("/favicon.ico"));
    }

    @Test
    void versionQueryStringDoesNotAffectMatching() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cards/ace_of_hearts.svg");
        request.setQueryString("v=2.0.14");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        assertEquals("public, max-age=31536000, immutable", response.getHeader("Cache-Control"));
    }

    @Test
    void apiAndWebSocketAreNeverStored() throws Exception {
        assertEquals("no-store", headerFor("/api/v1/version"));
        assertEquals("no-store", headerFor("/api/v1/rooms/open"));
        assertEquals("no-store", headerFor("/ws/info"));
        assertEquals("no-store", headerFor("/actuator/health"));
    }

    @Test
    void entryPointAndSpaRoutesRevalidate() throws Exception {
        assertEquals("no-cache", headerFor("/"));
        assertEquals("no-cache", headerFor("/index.html"));
        assertEquals("no-cache", headerFor("/home"));
        assertEquals("no-cache", headerFor("/join/ABC123"));
        assertEquals("no-cache", headerFor("/rules"));
        assertEquals("no-cache", headerFor("/login"));
    }
}
