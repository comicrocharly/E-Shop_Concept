package com.eshop.controller;

import com.eshop.config.CurrentUser;
import com.eshop.dto.CreateArticlesRequest;
import com.eshop.entity.Articles;
import com.eshop.entity.User;
import com.eshop.service.ArticlesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticlesController {

    private final ArticlesService articlesService;
    private final CurrentUser currentUser;

    @GetMapping
    public ResponseEntity<Page<Articles>> findAll(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice) {
        Page<Articles> page;
        boolean hasFilters = (category != null && !category.isBlank())
                || (minPrice != null)
                || (maxPrice != null);
        boolean hasSearch = (search != null && !search.isBlank());

        if (hasSearch && hasFilters) {
            page = articlesService.findBySearchAndFilters(search, category, minPrice, maxPrice, pageable);
        } else if (hasSearch) {
            page = articlesService.findBySearch(search, pageable);
        } else if (hasFilters) {
            page = articlesService.findByFilters(category, minPrice, maxPrice, pageable);
        } else {
            page = articlesService.findAll(pageable);
        }
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Articles> findById(@PathVariable Long id) {
        return ResponseEntity.ok(articlesService.findById(id));
    }

    @GetMapping("/by-author/{authorId}")
    public ResponseEntity<List<Articles>> findByAuthor(@PathVariable Long authorId) {
        return ResponseEntity.ok(articlesService.findByAuthorId(authorId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Articles> create(@Valid @RequestBody CreateArticlesRequest request) {
        Long userId = currentUser.getCurrentUserId();
        User author = getUser(userId);
        Articles articles = articlesService.create(request, author);
        return ResponseEntity.status(HttpStatus.CREATED).body(articles);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Articles> update(@PathVariable Long id,
                                          @Valid @RequestBody CreateArticlesRequest request) {
        Articles articles = articlesService.update(id, request);
        return ResponseEntity.ok(articles);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        articlesService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private User getUser(Long userId) {
        return currentUser.getCurrentUser();
    }
}
