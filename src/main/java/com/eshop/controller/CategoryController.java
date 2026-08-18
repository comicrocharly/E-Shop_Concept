package com.eshop.controller;

import com.eshop.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CategoryController {

    private final ArticlesService articlesService;

    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> getCategories() {
        Set<String> categories = articlesService.findDistinctCategories();
        return ResponseEntity.ok(Map.of("categories", categories));
    }
}
