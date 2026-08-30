package com.eshop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Serve le immagini degli articoli salvate su disk sotto /images/articles/{fileName}.
 * La route è già pubblica in SecurityConfig (/images/**).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.article-image-dir:data/article-images}")
    private Path storageDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = storageDir.toAbsolutePath().toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/images/articles/**")
                .addResourceLocations(location);
    }
}
