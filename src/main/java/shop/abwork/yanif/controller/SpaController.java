package shop.abwork.yanif.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller to serve the SPA (Single Page Application) for all non-API routes.
 * This allows React Router to handle client-side routing by forwarding
 * unknown paths to index.html.
 */
@Controller
public class SpaController {

    @GetMapping("/")
    public String root() {
        return "forward:/index.html";
    }

    @GetMapping(value = "/{*path}")
    public String index() {
        return "forward:/index.html";
    }
}