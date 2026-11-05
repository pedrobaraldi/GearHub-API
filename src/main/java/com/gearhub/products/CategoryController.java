package com.gearhub.products;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categorias", description = "Categorias, critérios de avaliação e campos do formulário de post")
public class CategoryController {

    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Lista as categorias com critérios e campos da ficha técnica")
    public List<CategoryResponse> list() {
        return categories.findAllByOrderByPosition().stream().map(CategoryResponse::from).toList();
    }

    @GetMapping("/{slug}")
    @Transactional(readOnly = true)
    @Operation(summary = "Busca uma categoria pelo slug")
    @ApiResponse(responseCode = "404", description = "Categoria não encontrada")
    public CategoryResponse get(@PathVariable String slug) {
        return categories.findBySlug(slug)
                .map(CategoryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria não encontrada"));
    }
}
