package com.example.obscura_backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(StaticResourceConfig.class);

    @Value("${photo.upload-dir:uploads}")
    private String uploadDirPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = Paths.get(uploadDirPath).toAbsolutePath().normalize();
        String uploadLocation = "file:///" + uploadPath.toString().replace("\\", "/") + "/";

        logger.info("Configuring static resource handler:");
        logger.info("  Upload directory: {}", uploadPath.toAbsolutePath());
        logger.info("  Resource location: {}", uploadLocation);
        logger.info("  URL pattern: /uploads/**");

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadLocation);
    }
}

