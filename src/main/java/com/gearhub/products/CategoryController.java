package com.gearhub.products;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categorias", description = "Categorias, critérios de avaliação e campos do formulário de post")
public class CategoryController {

    private final CategoryService categories;

    public CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @Operation(summary = "Lista as categorias com critérios e campos da ficha técnica")
    public List<CategoryResponse> list() {
        return categories.list();
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Busca uma categoria pelo slug")
    @ApiResponse(responseCode = "404", description = "Categoria não encontrada")
    public CategoryResponse get(@PathVariable String slug) {
        return categories.get(slug);
    }
}
