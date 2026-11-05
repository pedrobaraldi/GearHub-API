package com.gearhub.products;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Categoria, critérios de avaliação e campos da ficha técnica do post")
public record CategoryResponse(
        @Schema(example = "mouse") String slug,
        @Schema(example = "Mouse") String name,
        List<CriterionResponse> criteria,
        List<SpecFieldResponse> specFields) {

    public record CriterionResponse(Integer id, @Schema(example = "Sensor") String label) {
    }

    public record SpecFieldResponse(
            @Schema(example = "sensor") String key,
            @Schema(example = "Sensor") String label,
            @Schema(description = "Se o campo precisa ser preenchido para publicar o post") boolean required) {
    }

    static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getSlug(),
                category.getName(),
                category.getCriteria().stream()
                        .map(c -> new CriterionResponse(c.getId(), c.getLabel()))
                        .toList(),
                category.getSpecFields().stream()
                        .map(f -> new SpecFieldResponse(f.getKey(), f.getLabel(), f.isRequired()))
                        .toList());
    }
}
