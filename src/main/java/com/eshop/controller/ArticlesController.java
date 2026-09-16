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
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

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

    /**
     * Creazione articolo (multipart) con una o più immagini opzionali.
     * Alternative a POST JSON per chi carica anche le foto.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Articles> createWithImages(@RequestParam String name,
                                                     @RequestParam(required = false) String description,
                                                     @RequestParam BigDecimal price,
                                                     @RequestParam Integer stock,
                                                     @RequestParam(name = "images", required = false) MultipartFile[] images) {
        User author = getUser(currentUser.getCurrentUserId());
        Articles article = articlesService.createWithImages(name, description, price, stock, author, images);
        return ResponseEntity.status(HttpStatus.CREATED).body(article);
    }

    /**
     * Aggiunge una o più immagini a un articolo esistente.
     */
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Articles> addImages(@PathVariable Long id,
                                              @RequestParam(name = "images") MultipartFile[] images) {
        return ResponseEntity.ok(articlesService.addImages(id, images));
    }

    /**
     * Rimuove una singola immagine.
     */
    @DeleteMapping("/images/{imageId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeImage(@PathVariable Long imageId) {
        articlesService.removeImage(imageId);
        return ResponseEntity.noContent().build();
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
