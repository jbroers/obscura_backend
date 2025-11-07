package com.example.obscura_backend.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

@Component
public class RequestLoggingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        logger.info("=== Incoming Request ===");
        logger.info("Method: {}", httpRequest.getMethod());
        logger.info("URI: {}", httpRequest.getRequestURI());
        logger.info("Content-Type: {}", httpRequest.getContentType());
        logger.info("Content-Length: {}", httpRequest.getContentLength());

        logger.info("Headers:");
        Collections.list(httpRequest.getHeaderNames()).forEach(headerName ->
            logger.info("  {}: {}", headerName, httpRequest.getHeader(headerName))
        );

        logger.info("Parameters:");
        httpRequest.getParameterMap().forEach((key, value) ->
            logger.info("  {}: {}", key, String.join(",", value))
        );

        if (httpRequest.getContentType() != null && httpRequest.getContentType().contains("multipart/form-data")) {
            try {
                logger.info("Multipart Parts:");
                for (Part part : httpRequest.getParts()) {
                    logger.info("  Part name: {}, size: {}, content-type: {}, filename: {}",
                        part.getName(),
                        part.getSize(),
                        part.getContentType(),
                        part.getSubmittedFileName());
                }
            } catch (Exception e) {
                logger.warn("Could not read multipart parts: {}", e.getMessage());
            }
        }

        logger.info("======================");

        chain.doFilter(request, response);
    }
}

