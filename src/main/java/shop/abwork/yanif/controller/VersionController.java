package shop.abwork.yanif.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller to expose application version information.
 * Version is defined in application.properties (app.version).
 */
@RestController
public class VersionController {

    @Value("${app.version:1.0.0}")
    private String version;

    @GetMapping("/api/v1/version")
    public ResponseEntity<Map<String, String>> getVersion() {
        return ResponseEntity.ok(Map.of(
                "version", version,
                "name", "Yanif"
        ));
    }
}