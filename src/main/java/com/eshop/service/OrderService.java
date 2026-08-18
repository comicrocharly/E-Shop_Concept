package com.eshop.service;

import com.eshop.dto.*;
import com.eshop.entity.*;
import com.eshop.enums.OrderStatus;
import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;
import com.eshop.repository.ArticlesRepository;
import com.eshop.repository.CartRepository;
import com.eshop.repository.OrderPaymentRepository;
import com.eshop.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final CartRepository cartRepository;
    private final ArticlesRepository articlesRepository;
    private final UserService userService;
    private final PaymentGatewayService paymentGateway;

    // ========== READ OPERATIONS ==========

    @Transactional(readOnly = true)
    public List<Order> getOrdersByUserId(Long userId) {
        return orderRepository.findByUserIdWithItems(userId);
    }

    @Transactional(readOnly = true)
    public Page<Order> getOrdersByUserIdPaginated(Long userId, Pageable pageable) {
        return orderRepository.findByUserIdWithPageable(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Order> getOrdersByUserIdAndStatusPaginated(Long userId, OrderStatus status, Pageable pageable) {
        return orderRepository.findByUserIdAndStatus(userId, status, pageable);
    }

    @Transactional(readOnly = true)
    public Order findById(Long orderId) {
        return orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Ordine non trovato: " + orderId));
    }

    // ========== NEW CHECKOUT FLOW (2 STEP) ==========

    /**
     * Step 1: Prepara l'ordine.
     * - Verifica stock disponibile
     * - Crea ordine con status PENDING
     * - Riserva lo stock (ma non lo riduce)
     * - Svuota il carrello
     */
    @Transactional
    public PrepareCheckoutResponse prepareCheckout(Long userId, PaymentMethod paymentMethod) {
        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> new IllegalStateException("Carrello non trovato per utente: " + userId));
        User user = cart.getUser();

        List<CartItem> cartItems = new ArrayList<>(cart.getItems());
        if (cartItems.isEmpty()) {
            throw new IllegalStateException("Il carrello è vuoto");
        }

        // 1. Verifica stock disponibile
        int totalReserved = 0;
        for (CartItem cartItem : cartItems) {
            Articles articles = cartItem.getArticles();
            int required = cartItem.getQuantity();
            if (articles.getStock() < required) {
                throw new IllegalStateException(
                        "Stock insufficiente per '" + articles.getName() +
                                "'. Disponibile: " + articles.getStock() +
                                ", Richiesto: " + required);
            }
            totalReserved += required;
        }

        // 2. Calcola totale
        BigDecimal total = cartItems.stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Crea l'ordine
        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.PENDING)
                .total(total)
                .paymentMethod(paymentMethod)
                .reservedStock(totalReserved)
                .items(new ArrayList<>())
                .build();

        // 4. Crea OrderItem
        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .articles(cartItem.getArticles())
                    .quantity(cartItem.getQuantity())
                    .unitPrice(cartItem.getUnitPrice())
                    .build();
            order.getItems().add(orderItem);
        }

        order = orderRepository.save(order);

        // 5. Svuota il carrello (stock non toccato, solo riservato)
        cart.clear();
        cartRepository.save(cart);

        return new PrepareCheckoutResponse(
                order.getId(),
                total,
                cartItems.stream().map(ci -> new PrepareCheckoutResponse.CartItemDto(
                        ci.getArticles().getId(),
                        ci.getArticles().getName(),
                        ci.getQuantity(),
                        ci.getUnitPrice(),
                        ci.getSubtotal()
                )).toList(),
                paymentMethod
        );
    }

    /**
     * Step 2: Completa il pagamento.
     * - Chiede al gateway di pagamento
     * - Se successo: riduce lo stock, cambia status a PROCESSING, crea OrderPayment
     * - Se fallimento: annulla prenotazione, cancella ordine
     */
    @Transactional
    public PayOrderResponse completePayment(Long orderId, PaymentMethod method, Map<String, String> details) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Ordine non trovato: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Ordine già elaborato o cancellato. Status: " + order.getStatus());
        }

        // Chiamata al mock gateway
        GatewayResult result = paymentGateway.processPayment(method, order.getTotal(), details);

        if (!result.success()) {
            // Pagamento fallito: cancella ordine
            cancelOrder(orderId);
            throw new RuntimeException("Pagamento fallito: " + result.status());
        }

        // Pagamento riuscito: riduci stock
        int releasedStock = order.getReservedStock();
        for (OrderItem item : order.getItems()) {
            Articles articles = item.getArticles();
            int newStock = articles.getStock() - item.getQuantity();
            if (newStock < 0) {
                // Rollback: qualcuno ha comprato nello stesso istante
                // Riporta lo stock
                for (OrderItem rollbackItem : order.getItems()) {
                    rollbackItem.getArticles().setStock(
                            rollbackItem.getArticles().getStock() + rollbackItem.getQuantity());
                    articlesRepository.save(rollbackItem.getArticles());
                }
                throw new IllegalStateException("Stock insufficiente al momento del pagamento. Ordine cancellato.");
            }
            articles.setStock(newStock);
            articlesRepository.save(articles);
        }

        // Crea e salva OrderPayment PRIMA dell'Order (evita TransientObjectException)
        OrderPayment payment = OrderPayment.builder()
                .order(order)
                .method(method)
                .amount(order.getTotal())
                .status(PaymentStatus.valueOf(result.status()))
                .transactionId(result.transactionId())
                .details(details != null ? details.toString() : null)
                .build();

        // Aggiorna ordine
        order.setPayment(payment);
        order.setReservedStock(0);
        order.setStatus(OrderStatus.PROCESSING);

        // Salva il pagamento (deve essere salvato PRIMA o insieme all'Order)
        payment = orderPaymentRepository.save(payment);
        order.setPayment(payment);
        order = orderRepository.save(order);

        return new PayOrderResponse(
                order.getId(),
                payment.getStatus(),
                result.transactionId(),
                order.getTotal(),
                method
        );
    }

    /**
     * Annulla un ordine PENDING (es. pagamento fallito).
     */
    @Transactional
    public void cancelOrder(Long orderId) {
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isPresent()) {
            Order order = opt.get();
            if (order.getStatus() == OrderStatus.PENDING) {
                // Riporta lo stock
                for (OrderItem item : order.getItems()) {
                    Articles articles = item.getArticles();
                    articles.setStock(articles.getStock() + item.getQuantity());
                    articlesRepository.save(articles);
                }
                order.setStatus(OrderStatus.CANCELLED);
                order.setReservedStock(0);
                orderRepository.save(order);
            }
        }
    }

    // ========== LEGACY: ONE-STEP CHECKOUT (deprecated, still used by tests) ==========

    /**
     * Legacy checkout: prepara + paga in un solo passo (usato dai test).
     */
    @Transactional
    public Order checkout(Long userId) {
        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> new IllegalStateException("Carrello non trovato per utente: " + userId));
        User user = cart.getUser();

        List<CartItem> cartItems = new ArrayList<>(cart.getItems());
        if (cartItems.isEmpty()) {
            throw new IllegalStateException("Il carrello è vuoto");
        }

        // 1. Verifica stock disponibile per tutti gli articoli
        for (CartItem cartItem : cartItems) {
            Articles articles = cartItem.getArticles();
            int required = cartItem.getQuantity();
            if (articles.getStock() < required) {
                throw new IllegalStateException(
                        "Stock insufficiente per '" + articles.getName() +
                                "'. Disponibile: " + articles.getStock() +
                                ", Richiesto: " + required);
            }
        }

        // 2. Decrementa lo stock e salva
        for (CartItem cartItem : cartItems) {
            Articles articles = cartItem.getArticles();
            articles.setStock(articles.getStock() - cartItem.getQuantity());
            articlesRepository.save(articles);
        }

        // 3. Calcola il totale
        BigDecimal total = cartItems.stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 4. Crea l'ordine
        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.PENDING)
                .total(total)
                .items(new ArrayList<>())
                .build();

        // 5. Crea gli OrderItem e salva l'ordine
        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .articles(cartItem.getArticles())
                    .quantity(cartItem.getQuantity())
                    .unitPrice(cartItem.getUnitPrice())
                    .build();
            order.getItems().add(orderItem);
        }

        order = orderRepository.save(order);

        // 6. Svuota il carrello
        cart.clear();
        cartRepository.save(cart);

        return order;
    }

    // ========== ADMIN & READ OPERATIONS ==========

    @Transactional(readOnly = true)
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public Page<Order> getOrdersByStatusPaginated(OrderStatus status, Pageable pageable) {
        return orderRepository.findByStatusWithPageable(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Order> getOrdersByStatusAndSearch(OrderStatus status, String search, Pageable pageable) {
        if (search != null && !search.isBlank()) {
            return orderRepository.findByStatusAndSearch(status, search, pageable);
        }
        return orderRepository.findByStatusWithPageable(status, pageable);
    }

    @Transactional
    public Order updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Ordine non trovato: " + orderId));

        OrderStatus currentStatus = order.getStatus();
        if (!OrderStatus.isValidTransition(currentStatus, newStatus)) {
            throw new IllegalStateException(
                    "Transizione di stato non valida: da " + currentStatus + " a " + newStatus);
        }

        order.setStatus(newStatus);
        return orderRepository.save(order);
    }

    /**
     * L'utente conferma la ricezione dell'ordine (DELIVERED → COMPLETED).
     */
    @Transactional
    public Order markAsCompleted(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Ordine non trovato: " + orderId));

        // Verifica proprietà
        if (!order.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Non sei autorizzato a completare questo ordine");
        }

        // Verifica stato attuale (DEVE essere DELIVERED)
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new IllegalStateException(
                    "Solo gli ordini nello stato DELIVERED possono essere completati. Status attuale: " + order.getStatus());
        }

        order.setStatus(OrderStatus.COMPLETED);
        return orderRepository.save(order);
    }
}
