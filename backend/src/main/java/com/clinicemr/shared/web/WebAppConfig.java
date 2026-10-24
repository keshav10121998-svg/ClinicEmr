package com.clinicemr.shared.web;

import java.io.IOException;
import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the web app (clinic and patient screens) from {@code classpath:/static}, which {@code mvnw package} fills
 * from {@code frontend/dist}. Screen URLs such as {@code /patients/…} or {@code /my} get {@code index.html} so the
 * app's router can take over. API paths are never answered with the app.
 */
@Configuration
public class WebAppConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // File names under /assets carry a content hash, so they can be cached for good
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource file = location.createRelative(path);
                        if (file.exists() && file.isReadable()) {
                            return file;
                        }
                        if (path.startsWith("api/") || path.startsWith("actuator/") || path.startsWith("assets/")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
