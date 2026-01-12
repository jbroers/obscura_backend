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

        String logMessage = String.format("%s %s", httpRequest.getMethod(), httpRequest.getRequestURI());

        if (httpRequest.getContentType() != null && httpRequest.getContentType().contains("multipart/form-data")) {
            try {
                for (Part part : httpRequest.getParts()) {
                    if (part.getSubmittedFileName() != null) {
                        logMessage += String.format(" [%s, %.2f MB]",
                            part.getSubmittedFileName(),
                            part.getSize() / 1024.0 / 1024.0);
                    }
                }
            } catch (Exception e) {
            }
        }

        logger.info(logMessage);

        chain.doFilter(request, response);
    }
}

