package com.eshop.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "articles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Articles {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 50)
    private String category;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    @JsonIgnore
    private User author;

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<ArticleImage> images = new ArrayList<>();

    public String getAuthorUsername() {
        return author != null ? author.getUsername() : null;
    }

    public Long getAuthorId() {
        return author != null ? author.getId() : null;
    }

    /** URL dell'immagine di anteprima (posizione minima), o null se non ci sono immagini */
    public String getPreviewImage() {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream()
                .min(Comparator.comparing(ArticleImage::getPosition, Comparator.nullsLast(Integer::compareTo)))
                .map(ArticleImage::getUrl)
                .orElse(null);
    }
}
