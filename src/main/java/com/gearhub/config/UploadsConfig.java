package com.gearhub.config;

import com.gearhub.reviews.ContentImageService;
import com.gearhub.users.AvatarService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class UploadsConfig implements WebMvcConfigurer {

    private final AvatarService avatars;
    private final ContentImageService images;

    public UploadsConfig(AvatarService avatars, ContentImageService images) {
        this.avatars = avatars;
        this.images = images;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(AvatarService.PUBLIC_PATH + "**")
                .addResourceLocations(avatars.directory().toUri().toString() + "/");
        registry.addResourceHandler(ContentImageService.PUBLIC_PATH + "**")
                .addResourceLocations(images.directory().toUri().toString() + "/");
    }
}
