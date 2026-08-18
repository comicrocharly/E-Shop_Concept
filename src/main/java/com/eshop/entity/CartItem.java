package com.eshop.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "cart_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    @JsonIgnore
    private Cart cart;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "article_id", nullable = false)
    private Articles articles;

    @Column(nullable = false)
    private Integer quantity;

    @Column(precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /**
     * Il prezzo del carrello è sempre sincronizzato con l'ultimo prezzo dell'articolo.
     * Questo garantisce che se l'admin aggiorna il prezzo, il carrello mostra il prezzo aggiornato.
     * All'checkout, il prezzo viene bloccato nel OrderItem (che non viene più aggiornato).
     */
    @PrePersist
    @PreUpdate
    protected void onPersistOrUpdate() {
        // Sempre sincronizza con l'ultimo prezzo dell'articolo
        this.unitPrice = this.articles.getPrice();
    }

    public BigDecimal getSubtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
