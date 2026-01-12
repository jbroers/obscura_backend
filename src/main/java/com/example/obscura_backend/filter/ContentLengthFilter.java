package com.example.obscura_backend.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class ContentLengthFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(ContentLengthFilter.class);

    @Value("${spring.servlet.multipart.max-request-size}")
    private String maxRequestSizeRaw;

    private long maxBytes = -1L;

    private long parseSize(String raw) {
        if (raw == null) return 10L * 1024L * 1024L;
        raw = raw.trim().toUpperCase();
        if (raw.endsWith("MB")) {
            return Long.parseLong(raw.substring(0, raw.length() - 2).trim()) * 1024L * 1024L;
        } else if (raw.endsWith("KB")) {
            return Long.parseLong(raw.substring(0, raw.length() - 2).trim()) * 1024L;
        } else if (raw.endsWith("B")) {
            return Long.parseLong(raw.substring(0, raw.length() - 1).trim());
        } else {
            return Long.parseLong(raw);
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (maxBytes < 0) {
            maxBytes = parseSize(maxRequestSizeRaw);
            logger.info("Max request size: {}", maxRequestSizeRaw);
        }

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String cl = request.getHeader("Content-Length");
        if (cl != null) {
            try {
                long length = Long.parseLong(cl);
                if (length > maxBytes) {
                    logger.warn("Request rejected: {} > {}", formatSize(length), maxRequestSizeRaw);
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"The uploaded file(s) exceed the maximum allowed size of " + maxRequestSizeRaw + ".\"}");
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        chain.doFilter(req, res);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.2f KB", bytes / 1024.0);
    }
}

