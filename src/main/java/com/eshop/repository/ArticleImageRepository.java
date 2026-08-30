package com.eshop.repository;

import com.eshop.entity.ArticleImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArticleImageRepository extends JpaRepository<ArticleImage, Long> {

    List<ArticleImage> findByArticleIdOrderByPositionAsc(Long articleId);
}
