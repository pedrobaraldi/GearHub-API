package com.gearhub.products;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Integer> {

    List<Category> findAllByOrderByPosition();

    Optional<Category> findBySlug(String slug);
}
