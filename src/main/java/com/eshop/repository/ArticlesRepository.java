package com.eshop.repository;

import com.eshop.entity.Articles;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Repository
public interface ArticlesRepository extends JpaRepository<Articles, Long> {

    @Query("SELECT a FROM Articles a WHERE a.author.id = :authorId")
    List<Articles> findByAuthorId(@Param("authorId") Long authorId);

    @Query("SELECT a FROM Articles a WHERE LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(a.description) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Articles> findBySearch(@Param("search") String search, Pageable pageable);

    // --- Category + Price Filters ---

    @Query("SELECT DISTINCT a.category FROM Articles a WHERE a.category IS NOT NULL AND a.category <> ''")
    Set<String> findDistinctCategories();

    @Query("SELECT a FROM Articles a WHERE " +
           "(:category IS NULL OR :category = '' OR a.category = :category) " +
           "AND (:minPrice IS NULL OR a.price >= :minPrice) " +
           "AND (:maxPrice IS NULL OR a.price <= :maxPrice)")
    Page<Articles> findByFilters(
            @Param("category") String category,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            Pageable pageable);

    @Query("SELECT a FROM Articles a WHERE " +
           "(:category IS NULL OR :category = '' OR a.category = :category) " +
           "AND (:minPrice IS NULL OR a.price >= :minPrice) " +
           "AND (:maxPrice IS NULL OR a.price <= :maxPrice) " +
           "AND (LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(a.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Articles> findBySearchAndFilters(
            @Param("search") String search,
            @Param("category") String category,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            Pageable pageable);
}
