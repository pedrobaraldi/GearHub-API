package com.gearhub.products;

import com.gearhub.products.CategoryResponse.CriterionResponse;
import com.gearhub.products.CategoryResponse.SpecFieldResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Categorias com os critérios de nota e os campos da ficha técnica, em três consultas no total. */
@Service
public class CategoryService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public CategoryService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private record CategoryRow(int id, String slug, String name) {
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return withDetails(jdbc.sql("SELECT id, slug, name FROM categories ORDER BY position")
                .query((rs, row) -> new CategoryRow(rs.getInt("id"), rs.getString("slug"), rs.getString("name")))
                .list());
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(String slug) {
        List<CategoryRow> found = jdbc.sql("SELECT id, slug, name FROM categories WHERE slug = :slug")
                .param("slug", slug)
                .query((rs, row) -> new CategoryRow(rs.getInt("id"), rs.getString("slug"), rs.getString("name")))
                .list();
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria não encontrada");
        }
        return withDetails(found).getFirst();
    }

    private List<CategoryResponse> withDetails(List<CategoryRow> categories) {
        if (categories.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = categories.stream().map(CategoryRow::id).toList();
        Map<Integer, List<CriterionResponse>> criteria = new HashMap<>();
        jdbc.sql("SELECT id, category_id, label FROM criteria WHERE category_id IN (:ids) ORDER BY position")
                .param("ids", ids)
                .query(rs -> {
                    criteria.computeIfAbsent(rs.getInt("category_id"), id -> new ArrayList<>())
                            .add(new CriterionResponse(rs.getInt("id"), rs.getString("label")));
                });
        Map<Integer, List<SpecFieldResponse>> fields = new HashMap<>();
        jdbc.sql("""
                SELECT category_id, key, label, required, options::text AS options
                FROM spec_fields WHERE category_id IN (:ids) ORDER BY position
                """)
                .param("ids", ids)
                .query(rs -> {
                    fields.computeIfAbsent(rs.getInt("category_id"), id -> new ArrayList<>())
                            .add(new SpecFieldResponse(rs.getString("key"), rs.getString("label"),
                                    rs.getBoolean("required"), options(rs.getString("options"))));
                });
        return categories.stream()
                .map(category -> new CategoryResponse(category.slug(), category.name(),
                        criteria.getOrDefault(category.id(), List.of()), fields.getOrDefault(category.id(), List.of())))
                .toList();
    }

    private List<String> options(String optionsJson) {
        return optionsJson == null ? List.of() : json.readValue(optionsJson, new TypeReference<List<String>>() {
        });
    }
}
