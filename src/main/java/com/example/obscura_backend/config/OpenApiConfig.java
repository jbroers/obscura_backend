package com.example.obscura_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for OpenAPI/Swagger documentation.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Creates custom OpenAPI configuration with API metadata.
     *
     * @return Configured OpenAPI instance
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Obscura Backend API")
                        .version("1.0.0")
                        .description("REST API voor foto beheer en metadata extractie. " +
                                "Ondersteunt JPEG en RAW formaten met EXIF data extractie.")
                        .contact(new Contact()
                                .name("Obscura Team")));
    }
}

