package com.eshop.service;

import com.eshop.dto.CreateArticlesRequest;
import com.eshop.entity.Articles;
import com.eshop.entity.User;
import com.eshop.repository.ArticlesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ArticlesService {

    private final ArticlesRepository articlesRepository;

    @Transactional(readOnly = true)
    public List<Articles> findAll() {
        return articlesRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Page<Articles> findAll(Pageable pageable) {
        return articlesRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Articles> findBySearch(String search, Pageable pageable) {
        return articlesRepository.findBySearch(search, pageable);
    }

    // --- Category + Price Filters ---

    @Transactional(readOnly = true)
    public Set<String> findDistinctCategories() {
        return articlesRepository.findDistinctCategories();
    }

    @Transactional(readOnly = true)
    public Page<Articles> findByFilters(String category, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return articlesRepository.findByFilters(category, minPrice, maxPrice, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Articles> findBySearchAndFilters(String search, String category, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return articlesRepository.findBySearchAndFilters(search, category, minPrice, maxPrice, pageable);
    }

    @Transactional(readOnly = true)
    public Articles findById(Long id) {
        return articlesRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Articolo non trovato: " + id));
    }

    @Transactional(readOnly = true)
    public List<Articles> findByAuthorId(Long authorId) {
        return articlesRepository.findByAuthorId(authorId);
    }

    @Transactional
    public Articles create(CreateArticlesRequest request, User author) {
        validateRequest(request);

        Articles articles = Articles.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stock(request.stock())
                .author(author)
                .build();
        return articlesRepository.save(articles);
    }

    @Transactional
    public Articles update(Long id, CreateArticlesRequest request) {
        Articles existing = findById(id);
        existing.setName(request.name());
        existing.setDescription(request.description());
        existing.setPrice(request.price());
        existing.setStock(request.stock());
        return articlesRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!articlesRepository.existsById(id)) {
            throw new IllegalArgumentException("Articolo non trovato: " + id);
        }
        articlesRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return articlesRepository.existsById(id);
    }

    private void validateRequest(CreateArticlesRequest request) {
        if (request.price().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Il prezzo deve essere positivo");
        }
        if (request.stock() < 0) {
            throw new IllegalArgumentException("Lo stock non può essere negativo");
        }
    }
}
