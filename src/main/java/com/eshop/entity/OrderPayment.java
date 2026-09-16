package com.eshop.entity;

import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.Enumerated;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "order_payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String transactionId;
    private String details;

    private Instant authorizedAt;
    private Instant capturedAt;

    @PrePersist
    protected void onCreate() {
        this.status = PaymentStatus.PENDING;
        this.authorizedAt = Instant.now();
    }

    public boolean isCaptured() {
        return this.status == PaymentStatus.CAPTURED;
    }

    public boolean isAuthorized() {
        return this.status == PaymentStatus.AUTHORIZED;
    }

    public boolean isPending() {
        return this.status == PaymentStatus.PENDING;
    }
}
