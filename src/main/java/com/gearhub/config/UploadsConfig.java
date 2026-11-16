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
    private final com.gearhub.marketplace.StoreImageService storeImages;

    public UploadsConfig(AvatarService avatars, ContentImageService images, com.gearhub.marketplace.StoreImageService storeImages) {
        this.avatars = avatars;
        this.images = images;
        this.storeImages = storeImages;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(AvatarService.PUBLIC_PATH + "**")
                .addResourceLocations(avatars.directory().toUri().toString() + "/");
        registry.addResourceHandler(ContentImageService.PUBLIC_PATH + "**")
                .addResourceLocations(images.directory().toUri().toString() + "/");
        // Documentos de parceiros (partner-docs) nunca são publicados aqui: só saem pela API do admin
        registry.addResourceHandler(com.gearhub.marketplace.StoreImageService.PUBLIC_PATH + "**")
                .addResourceLocations(storeImages.directory().toUri().toString() + "/");
    }
}
