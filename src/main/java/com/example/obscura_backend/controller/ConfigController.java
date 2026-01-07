package com.example.obscura_backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
@Tag(name = "Configuration", description = "Application configuration endpoints")
public class ConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ConfigController.class);

    @Value("${server.base-url}")
    private String configuredBackendUrl;

    @Value("${spring.servlet.multipart.max-request-size}")
    private String maxRequestSizeRaw;

    private long parseSize(String raw) {
        if (raw == null) return 50L * 1024L * 1024L;
        raw = raw.trim().toUpperCase();
        try {
            if (raw.endsWith("MB")) {
                return Long.parseLong(raw.substring(0, raw.length() - 2).trim()) * 1024L * 1024L;
            } else if (raw.endsWith("KB")) {
                return Long.parseLong(raw.substring(0, raw.length() - 2).trim()) * 1024L;
            } else if (raw.endsWith("B")) {
                return Long.parseLong(raw.substring(0, raw.length() - 1).trim());
            } else {
                return Long.parseLong(raw);
            }
        } catch (NumberFormatException e) {
            logger.warn("Could not parse size '{}', defaulting to 50MB", raw);
            return 50L * 1024L * 1024L;
        }
    }

    @GetMapping("/config")
    @Operation(summary = "Get application configuration",
               description = "Returns backend URL and maximum file size configuration")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved configuration",
                content = @Content(mediaType = "application/json"))
    })
    public ResponseEntity<Map<String, Object>> getConfig(HttpServletRequest request) {
        String backendUrl = configuredBackendUrl;
        if (backendUrl == null || backendUrl.isBlank()) {
            String scheme = request.getScheme();
            int port = request.getServerPort();
            String host = request.getServerName();
            backendUrl = scheme + "://" + host + (port == 80 || port == 443 ? "" : ":" + port);
        }

        long maxBytes = parseSize(maxRequestSizeRaw);

        Map<String, Object> body = new HashMap<>();
        body.put("backendUrl", backendUrl);
        body.put("maxFileSize", maxRequestSizeRaw);
        body.put("maxFileSizeBytes", maxBytes);

        return ResponseEntity.ok(body);
    }
}

