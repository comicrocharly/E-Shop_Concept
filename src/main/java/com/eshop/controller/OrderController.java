package com.eshop.controller;

import com.eshop.config.CurrentUser;
import com.eshop.dto.*;
import com.eshop.entity.Order;
import com.eshop.enums.OrderStatus;
import com.eshop.enums.PaymentMethod;
import com.eshop.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;
    private final CurrentUser currentUser;

    // ?testUserId è attivo solo se esplicitamente abilitato (application-test.properties)
    @Value("${app.security.allow-test-userid:false}")
    private boolean testUserIdAllowed;

    // ========== NEW CHECKOUT FLOW (2 STEP) ==========

    /**
     * Step 1: Prepara l'ordine. Crea l'ordine, riserva lo stock, svuota il carrello.
     */
    @PostMapping("/checkout/prepare")
    public ResponseEntity<PrepareCheckoutResponse> prepareCheckout(
            @RequestParam(required = false) Long testUserId) {
        PaymentMethod method = PaymentMethod.CREDIT_CARD; // default
        Long userId = (testUserIdAllowed && testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        PrepareCheckoutResponse response = orderService.prepareCheckout(userId, method);
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: Completa il pagamento. Riduce lo stock, cambia status a PROCESSING.
     */
    @PostMapping("/{id}/pay")
    public ResponseEntity<PayOrderResponse> payOrder(
            @PathVariable Long id,
            @RequestBody PayOrderRequest request,
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserIdAllowed && testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        // Verify ownership
        Order order = orderService.findById(id);
        if (!order.getUser().getId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        PayOrderResponse response = orderService.completePayment(id, request.method(), request.details());
        return ResponseEntity.ok(response);
    }

    // ========== LEGACY: ONE-STEP CHECKOUT (backward compatible for tests) ==========

    @PostMapping("/checkout")
    public ResponseEntity<Order> checkout(
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserIdAllowed && testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        Order order = orderService.checkout(userId);
        return ResponseEntity.ok(order);
    }

    // ========== READ ORDERS ==========

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Order>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> findById(@PathVariable Long id,
                                          @RequestParam(required = false) Long testUserId) {
        Order order = orderService.findById(id);
        if (testUserIdAllowed && testUserId != null && !order.getUser().getId().equals(testUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/my")
    public ResponseEntity<Page<Order>> getMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "orderDate,desc") String sort,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserIdAllowed && testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        String sortField = sort.contains(",") ? sort.substring(0, sort.indexOf(',')) : "orderDate";
        Sort.Direction sortDir = Sort.Direction.fromString(
                sort.contains(",") ? sort.substring(sort.indexOf(',') + 1) : "DESC"
        );
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDir, sortField));
        Page<Order> orderPage;
        if (status != null) {
            orderPage = orderService.getOrdersByUserIdAndStatusPaginated(userId, status, pageable);
        } else {
            orderPage = orderService.getOrdersByUserIdPaginated(userId, pageable);
        }
        return ResponseEntity.ok(orderPage);
    }

    // ========== ADMIN: Order Status Management ==========

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AdminOrderDto>> getAllOrdersWithStatus(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "orderDate,desc") String sort,
            @RequestParam(required = false) Long testUserId) {
        String sortField = sort.contains(",") ? sort.substring(0, sort.indexOf(',')) : "orderDate";
        Sort.Direction sortDir = Sort.Direction.fromString(
                sort.contains(",") ? sort.substring(sort.indexOf(',') + 1) : "DESC"
        );
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDir, sortField));
        Page<Order> orderPage;
        if (status != null) {
            orderPage = orderService.getOrdersByStatusAndSearch(status, search, pageable);
        } else {
            if (search != null && !search.isBlank()) {
                orderPage = orderService.getOrdersByStatusAndSearch(null, search, pageable);
            } else {
                orderPage = orderService.getOrdersByStatusPaginated(null, pageable);
            }
        }
        Page<AdminOrderDto> result = orderPage.map(o -> new AdminOrderDto(
                o.getId(),
                o.getOrderDate(),
                o.getStatus(),
                o.getTotal(),
                o.getUser() != null ? o.getUser().getUsername() : "N/A",
                o.getItems().stream()
                        .map(i -> new OrderItemDto(
                                i.getId(),
                                i.getArticles() != null ? i.getArticles().getName() : "Articolo",
                                i.getQuantity(),
                                i.getUnitPrice()
                        ))
                        .toList()
        ));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Order> updateOrderStatus(
            @PathVariable Long id,
            @RequestParam OrderStatus status) {
        Order updated = orderService.updateOrderStatus(id, status);
        return ResponseEntity.ok(updated);
    }

    // ========== USER: Cancel Order ==========

    /**
     * Annulla un ordine PENDING.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Order> cancelOrder(
            @PathVariable Long id,
            @RequestParam(required = false) Long testUserId) {
        Order order = orderService.findById(id);
        if (testUserIdAllowed && testUserId != null && !order.getUser().getId().equals(testUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        orderService.cancelOrder(id);
        return ResponseEntity.ok(orderService.findById(id));
    }

    // ========== USER: Confirm Order Completion ==========

    /**
     * L'utente conferma di aver ricevuto l'ordine (DELIVERED → COMPLETED).
     */
    @PutMapping("/{id}/complete")
    public ResponseEntity<Order> completeOrder(
            @PathVariable Long id,
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserIdAllowed && testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        Order updated = orderService.markAsCompleted(id, userId);
        return ResponseEntity.ok(updated);
    }
}
