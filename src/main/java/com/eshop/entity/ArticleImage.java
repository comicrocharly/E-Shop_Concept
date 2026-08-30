package com.eshop.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "article_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    @JsonIgnore
    private Articles article;

    /** Nome file univoco sulla disk (es. 3f2a1b....png), servito sotto /images/articles/ */
    @Column(nullable = false, unique = true)
    private String fileName;

    /** Ordine di visualizzazione (0 = immagine di anteprima) */
    @Column(nullable = false)
    private Integer position;

    /** URL pubblico dell'immagine */
    public String getUrl() {
        return "/images/articles/" + fileName;
    }
}
