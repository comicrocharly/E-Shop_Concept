package com.eshop.integration;

import com.eshop.dto.GatewayResult;
import com.eshop.entity.Order;
import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;
import com.eshop.enums.OrderStatus;
import com.eshop.service.PaymentGatewayService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4 — Integrazione full-stack: {@code /api/orders} (prepare → pay, checkout legacy,
 * cancel, /my, admin, complete, status).
 *
 * <p>Il gateway pagamento è sostituito con {@code @MockBean PaymentGatewayService}
 * per determinismo: niente sleep 200ms, niente fallimento casuale 1% di
 * {@code MockPaymentGateway}. La copertura "wiring bean reale" è delegata a
 * {@link PaymentGatewayRealIntegrationTest}.
 *
 * <p>Comportamenti attuali documentati (nessun fix in main, vedi REBUILD_PLAN §6):
 * <ul>
 *   <li>⚠ B1: cancel di un ordine PENDING <b>aumenta</b> lo stock (10→12 con qty 2):
 *       prepare "riserva" senza decrementare, cancel poi aggiunge di nuovo;</li>
 *   <li>⚠ B2: cancel di un ordine non-PENDING è no-op silenzioso (200, stato invariato);</li>
 *   <li>⚠ pay senza "method" nel body (niente @Valid su PayOrderRequest): OrderPayment
 *       con method=NULL su colonna NOT NULL → DataIntegrityViolation a commit → 500
 *       + rollback (l'ordine resta PENDING);</li>
 *   <li>⚠ B6: {@code OrderPayment.@PrePersist} forza {@code status = PENDING}
 *       <b>dopo</b> che il builder ha impostato lo status restituito dal gateway
 *       → la colonna (e la risposta /pay) sono SEMPRE PENDING, lo status del
 *       gateway (CAPTURED/AUTHORIZED) è perso;</li>
 *   <li>⚠ Il ramo "Il carrello è vuoto" di prepare/checkout è in effetti
 *       irraggiungibile: la query {@code findByUserIdWithItems} usa JOIN FETCH
 *       (inner join) sulla collezione items → una cart vuota non viene nemmeno
 *       trovata → 409 "Carrello non trovato per utente: X".</li>
 *   <li>⚠ cancel senza ?testUserId (autenticazione via Bearer) NON fa ownership check:
 *       un altro utente può cancellare l'ordine (il check esiste solo sul path legacy
 *       ?testUserId);</li>
 *   <li>⚠ GET /api/orders/{id} senza ?testUserId è leggibile anche da anonimi
 *       (endpoint read senza sicurezza esplicita, permitAll nel profilo test).</li>
 * </ul>
 */
class OrdersIntegrationTest extends IntegrationTestSupport {

    private static final String PW = "secret123";

    @Autowired
    @MockBean
    private PaymentGatewayService paymentGateway;

    @BeforeEach
    void cleanGatewayStubs() {
        reset(paymentGateway);
    }

    private Auth newUser() throws Exception {
        return login(register(PW), PW);
    }

    /** Aggiunge un articolo alla cart (API) e prepara il checkout. Ritorna orderId. */
    private long prepareOrder(Auth user, long articleId, int qty) throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(user.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", articleId, "quantity", qty))))
                .andExpect(status().isOk());
        MvcResult res = mockMvc.perform(
                        post("/api/orders/checkout/prepare").param("testUserId", String.valueOf(user.id())))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(res.getResponse().getContentAsString()).path("orderId").asLong();
    }

    private void stubGatewaySuccess(String status) {
        when(paymentGateway.processPayment(any(), any(), any())).thenReturn(
                new GatewayResult(true, status, "MOCK-TEST-1"));
    }

    private void stubGatewayFailure() {
        when(paymentGateway.processPayment(any(), any(), any())).thenReturn(
                new GatewayResult(false, "Simulazione errore gateway", null));
    }

    private Order order(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    // ==================== PREPARE ====================

    @Test
    void prepare_200_orderPending_reservedStock() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("ord-art", "10.00", 10);
        long orderId = prepareOrder(a, articleId, 2);

        assertThat(orderId).isPositive();
        // findByIdWithItems: JOIN FETCH items (in test thread, fuori sessione, serve
        // la fetch join per leggere items senza LazyInitializationException)
        Order o = orderRepository.findByIdWithItems(orderId).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(o.getTotal()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(o.getReservedStock()).isEqualTo(2);
        assertThat(o.getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD); // default prepare
        assertThat(orderPaymentRepository.findByOrderId(orderId)).isEmpty(); // nessun pagamento
        assertThat(o.getItems()).hasSize(1);
        assertThat(o.getItems().get(0).getUnitPrice()).isEqualByComparingTo(BigDecimal.TEN);
        // prepare NON tocca lo stock (solo "riserva")
        assertThat(stockOf(articleId)).isEqualTo(10);
        // la cart è stata svuotata; verifica via API perché findByUserIdWithItems
        // (JOIN FETCH) non trova cart vuote (inner join sulla collezione items)
        String cartJson = mockMvc.perform(get("/api/cart/me").param("testUserId", String.valueOf(a.id())))
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(cartJson).path("items").size()).isZero();
    }

    @Test
    void prepare_emptyCart_409() throws Exception {
        // ⚠ Comportamento attuale: la cart esiste (creata al register) ma è vuota;
        // la query JOIN FETCH (inner join) non la trova → 409 "Carrello non
        // trovato per utente: X". Il ramo "Il carrello è vuoto" è in effetti
        // irraggiungibile via API.
        Auth a = newUser();
        mockMvc.perform(post("/api/orders/checkout/prepare")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Carrello non trovato per utente: " + a.id()));
    }

    @Test
    void prepare_insufficientStock_409_cartUntouched() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("ord-lowstock", "5.00", 10);
        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", articleId, "quantity", 2))))
                .andExpect(status().isOk());
        setStock(articleId, 1); // esaurimento esterno tra add e prepare

        mockMvc.perform(post("/api/orders/checkout/prepare")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Stock insufficiente per 'ord-lowstock'. Disponibile: 1, Richiesto: 2"));

        // nessun ordine creato, cart intatta, stock invariato
        assertThat(orderRepository.findByUserIdWithItems(a.id())).isEmpty();
        assertThat(cartRepository.findByUserIdWithItems(a.id()).orElseThrow().getItems()).hasSize(1);
        assertThat(stockOf(articleId)).isEqualTo(1);
    }

    // ==================== PAY ====================

    @Test
    void prepareThenPay_card_200_processing_stockDecremented() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("pay-art", "10.00", 10);
        long orderId = prepareOrder(a, articleId, 2);
        stubGatewaySuccess("CAPTURED");

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                // ⚠ KNOWN BUG B6: @PrePersist di OrderPayment forza status=PENDING
                // sovrascrivendo il CAPTURED restituito dal gateway → la risposta
                // riporta SEMPRE PENDING (transactionId dimostra che il gateway
                // è stato interrogato e il risultato parzialmente persistito).
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.transactionId").value("MOCK-TEST-1"))
                .andExpect(jsonPath("$.method").value("CREDIT_CARD"));

        Order o = order(orderId);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(o.getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
        assertThat(stockOf(articleId)).isEqualTo(8);   // 10 - 2
        assertThat(o.getReservedStock()).isZero();
        // OrderPayment persistito (root della query → campi leggibili in test thread)
        var payment = orderPaymentRepository.findByOrderId(orderId);
        assertThat(payment).isPresent();
        assertThat(payment.get().getMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
        assertThat(payment.get().getTransactionId()).isEqualTo("MOCK-TEST-1");
        assertThat(payment.get().getAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
        // ⚠ KNOWN BUG B6: anche in DB lo status è PENDING (perso dal @PrePersist)
        assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void pay_bankTransfer_200_processing_paymentStatusPending_bugB6() throws Exception {
        // ⚠ KNOWN BUG B6: il gateway restituisce AUTHORIZED ma @PrePersist di
        // OrderPayment lo sovrascrive con PENDING → la risposta riporta PENDING.
        Auth a = newUser();
        long articleId = createArticle("pay-bank", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("AUTHORIZED");

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "BANK_TRANSFER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.method").value("BANK_TRANSFER"));
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(orderPaymentRepository.findByOrderId(orderId).get().getMethod())
                .isEqualTo(PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void pay_cod_200_processing_paymentStatusPending_bugB6() throws Exception {
        // ⚠ KNOWN BUG B6: il gateway restituisce CAPTURED ma la risposta riporta
        // PENDING (vedi OrderPayment.@PrePersist).
        Auth a = newUser();
        long articleId = createArticle("pay-cod", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("CAPTURED");

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "COD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.method").value("COD"));
        // ⚠ Comportamento attuale: pay NON aggiorna order.paymentMethod (restato il
        // default CREDIT_CARD impostato al prepare); il metodo COD vive solo
        // sull'OrderPayment.
        assertThat(order(orderId).getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
        assertThat(orderPaymentRepository.findByOrderId(orderId).get().getMethod())
                .isEqualTo(PaymentMethod.COD);
    }

    @Test
    void pay_alreadyPaid_409() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("pay-dup", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("CAPTURED");
        String body = json(Map.of("method", "CREDIT_CARD"));

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ordine già elaborato o cancellato. Status: PROCESSING"));
        assertThat(stockOf(articleId)).isEqualTo(4); // decrementato una sola volta
    }

    @Test
    void pay_gatewayFailure_500_rollbackPendingAndStock() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("pay-fail", "5.00", 10);
        long orderId = prepareOrder(a, articleId, 3);
        stubGatewayFailure();

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Pagamento fallito: Simulazione errore gateway"));

        // ⚠ ROLLBACK di tutto il TX (anche la cancelOrder interna al fallimento):
        // ordine PENDING, reservedStock invariato, stock intatto, nessun OrderPayment.
        Order o = order(orderId);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(o.getReservedStock()).isEqualTo(3);
        assertThat(stockOf(articleId)).isEqualTo(10);
        assertThat(orderPaymentRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void pay_missingMethod_500_currentBehavior() throws Exception {
        // ⚠ Comportamento attuale (mirror di S3 pay_missingMethod): body senza
        // "method" (niente @Valid su PayOrderRequest) → OrderPayment con method=NULL
        // su colonna NOT NULL → DataIntegrityViolation a commit → handler generico
        // → 500 + rollback.
        Auth a = newUser();
        long articleId = createArticle("pay-nomethod", "5.00", 10);
        long orderId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("CAPTURED");

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError());

        Order o = order(orderId);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(o.getReservedStock()).isEqualTo(1);
        assertThat(stockOf(articleId)).isEqualTo(10);
        assertThat(orderPaymentRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void pay_stockRaceGuard_409() throws Exception {
        // Guardia di race condition: stock scende sotto la quantità riservata tra
        // prepare e pay → ISE → 409 + rollback.
        Auth a = newUser();
        long articleId = createArticle("pay-race", "5.00", 10);
        long orderId = prepareOrder(a, articleId, 2);
        setStock(articleId, 1);
        stubGatewaySuccess("CAPTURED");

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Stock insufficiente al momento del pagamento. Ordine cancellato."));

        Order o = order(orderId);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stockOf(articleId)).isEqualTo(1);
        assertThat(orderPaymentRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void pay_notOwner_403() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        long articleId = createArticle("pay-notmine", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("CAPTURED");

        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(b.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void pay_unknownOrder_404() throws Exception {
        Auth a = newUser();
        mockMvc.perform(post("/api/orders/999999999/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ordine non trovato: 999999999"));
    }

    // ==================== CANCEL ====================

    @Test
    void cancelPending_200_cancelled_stockInflates_knownBugB1() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("canc-art", "10.00", 10);
        long orderId = prepareOrder(a, articleId, 2);

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Order o = order(orderId);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(o.getReservedStock()).isZero();
        // ⚠ KNOWN BUG B1 (comportamento attuale, documentato in S2 e confermato a
        // livello API): prepare NON decrementa lo stock; cancel aggiunge la quantità
        // "riservata" allo stock già integro → 10 → 12 (dovrebbe restare 10).
        assertThat(stockOf(articleId)).isEqualTo(12);
    }

    @Test
    void cancelViaJwt_otherUserCanCancel_noOwnershipCheck() throws Exception {
        // ⚠ Comportamento attuale: senza ?testUserId il controller NON fa ownership
        // check e il servizio non lo fa mai → un utente (Bearer JWT, ROLE_USER) può
        // cancellare l'ordine di un altro.
        Auth a = newUser();
        Auth b = newUser();
        long articleId = createArticle("canc-jwt", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelNonPending_200_noop_knownBugB2() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("canc-proc", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("CAPTURED");
        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isOk());

        // ⚠ KNOWN BUG B2: cancel su ordine PROCESSING è no-op silenzioso → 200
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(order(orderId).getReservedStock()).isZero();
        assertThat(stockOf(articleId)).isEqualTo(4); // invariato
    }

    @Test
    void cancel_notOwner_403() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        long articleId = createArticle("canc-notmine", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .param("testUserId", String.valueOf(b.id())))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancel_unknownOrder_404() throws Exception {
        Auth a = newUser();
        mockMvc.perform(post("/api/orders/999999999/cancel")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ordine non trovato: 999999999"));
    }

    // ==================== MY ORDERS (paginated) ====================

    @Test
    void myOrders_pageWithStatusFilter() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        long articleId = createArticle("my-ord", "5.00", 10);

        long pendingId = prepareOrder(a, articleId, 1);   // resta PENDING (non pagato)
        long processingId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("CAPTURED");
        mockMvc.perform(post("/api/orders/" + processingId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isOk());

        // A vede i propri 2 ordini (Page<Order>)
        JsonNode all = readJson(mockMvc.perform(get("/api/orders/my")
                        .param("testUserId", String.valueOf(a.id())))
                .andReturn().getResponse().getContentAsString());
        assertThat(all.path("totalElements").asInt()).isEqualTo(2);
        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode n : all.path("content")) {
            ids.add(n.path("id").asLong());
        }
        assertThat(ids).contains(pendingId, processingId);

        // filtro status
        JsonNode processing = readJson(mockMvc.perform(get("/api/orders/my")
                        .param("testUserId", String.valueOf(a.id()))
                        .param("status", "PROCESSING"))
                .andReturn().getResponse().getContentAsString());
        assertThat(processing.path("totalElements").asInt()).isEqualTo(1);
        assertThat(processing.path("content").get(0).path("id").asLong()).isEqualTo(processingId);

        // B non vede ordini
        String bOrdersJson = mockMvc.perform(get("/api/orders/my")
                        .param("testUserId", String.valueOf(b.id())))
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(bOrdersJson).path("totalElements").asInt()).isZero();
    }

    @Test
    void findById_withoutTestUserId_readableByAnyone_401nA_currentBehavior() throws Exception {
        // ⚠ Comportamento attuale: GET /api/orders/{id} senza ?testUserId non fa
        // ownership check e, nel profilo test (permitAll), è raggiungibile anche
        // senza autenticazione: qualsiasi ordine è leggibile da chiunque.
        Auth a = newUser();
        long articleId = createArticle("read-order", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);

        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ==================== CHECKOUT LEGACY ====================

    @Test
    void legacyCheckout_200_pending_stockDecrementedNoPayment() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("legacy-art", "10.00", 10);
        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", articleId, "quantity", 2))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/checkout")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reservedStock").value(0));

        Order legacy = orderRepository.findByUserIdWithItems(a.id()).stream().findFirst().orElseThrow();
        assertThat(legacy.getTotal()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(orderPaymentRepository.findByOrderId(legacy.getId())).isEmpty(); // nessun pagamento
        assertThat(legacy.getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD); // default campo entità
        assertThat(stockOf(articleId)).isEqualTo(8);   // decrementato subito (no reserve)
        // cart svuotata: verifica via API (findByUserIdWithItems non trova cart vuote)
        String cartJson = mockMvc.perform(get("/api/cart/me").param("testUserId", String.valueOf(a.id())))
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(cartJson).path("items").size()).isZero();
    }

    @Test
    void legacyCheckout_emptyCart_409() throws Exception {
        // ⚠ Comportamento attuale: cart vuota → JOIN FETCH non la trova →
        // "Carrello non trovato" (il ramo "Il carrello è vuoto" è irraggiungibile).
        Auth a = newUser();
        mockMvc.perform(post("/api/orders/checkout")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Carrello non trovato per utente: " + a.id()));
    }

    // ==================== ADMIN ====================

    @Test
    void adminOrders_pageWithUserAndItems() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("adm-art", "7.50", 8);
        long orderId = prepareOrder(a, articleId, 2);
        stubGatewaySuccess("CAPTURED");
        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isOk());

        JsonNode page = readJson(mockMvc.perform(get("/api/orders/admin")
                        .header("Authorization", "Bearer " + admin().accessToken())
                        .param("size", "100")).andReturn().getResponse().getContentAsString());
        JsonNode mine = findOrderIn(page.path("content"), orderId);
        assertThat(mine.path("username").asText()).isEqualTo(a.username());
        assertThat(mine.path("status").asText()).isEqualTo("PROCESSING");
        assertThat(mine.path("total").decimalValue()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(mine.path("items").get(0).path("articleName").asText()).isEqualTo("adm-art");
        assertThat(mine.path("items").get(0).path("quantity").asInt()).isEqualTo(2);

        // filtro status
        JsonNode filtered = readJson(mockMvc.perform(get("/api/orders/admin")
                        .header("Authorization", "Bearer " + admin().accessToken())
                        .param("size", "100")
                        .param("status", "PROCESSING")).andReturn().getResponse().getContentAsString());
        assertThat(findOrderIn(filtered.path("content"), orderId).path("id").asLong()).isEqualTo(orderId);
    }

    private JsonNode findOrderIn(JsonNode content, long orderId) {
        for (JsonNode n : content) {
            if (n.path("id").asLong() == orderId) return n;
        }
        throw new AssertionError("ordine " + orderId + " non presente nella pagina admin");
    }

    @Test
    void adminEndpoints_userRole_403() throws Exception {
        Auth a = newUser();
        // anonimi (probe C)
        mockMvc.perform(get("/api/orders/admin")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders")).andExpect(status().isForbidden());
        // utente non admin (Bearer)
        mockMvc.perform(get("/api/orders/admin").header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/orders/1/status").param("status", "PROCESSING")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminGetAllOrders_admin_200() throws Exception {
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + admin().accessToken()))
                .andExpect(status().isOk());
    }

    // ==================== ADMIN STATUS ====================

    @Test
    void adminUpdateStatus_chainAndInvalidTransition() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("adm-st", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);

        for (OrderStatus to : List.of(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)) {
            mockMvc.perform(put("/api/orders/" + orderId + "/status")
                            .param("status", to.name())
                            .header("Authorization", "Bearer " + admin().accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(to.name()));
        }
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.DELIVERED);

        // transizione non valida: PENDING → COMPLETED
        long otherId = prepareOrder(a, articleId, 1);
        mockMvc.perform(put("/api/orders/" + otherId + "/status")
                        .param("status", "COMPLETED")
                        .header("Authorization", "Bearer " + admin().accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Transizione di stato non valida: da PENDING a COMPLETED"));

        // ordine inesistente → 404
        mockMvc.perform(put("/api/orders/999999999/status")
                        .param("status", "PROCESSING")
                        .header("Authorization", "Bearer " + admin().accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ordine non trovato: 999999999"));
    }

    // ==================== COMPLETE ====================

    @Test
    void complete_delivered_200_completed() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("done-art", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);
        stubGatewaySuccess("CAPTURED");
        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "CREDIT_CARD"))))
                .andExpect(status().isOk());
        // PROCESSING → DELIVERED (transizione permessa) via admin
        mockMvc.perform(put("/api/orders/" + orderId + "/status")
                        .param("status", "DELIVERED")
                        .header("Authorization", "Bearer " + admin().accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/orders/" + orderId + "/complete")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void complete_pendingOrder_409() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("done-pend", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);

        mockMvc.perform(put("/api/orders/" + orderId + "/complete")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Solo gli ordini nello stato DELIVERED possono essere completati. Status attuale: PENDING"));
    }

    @Test
    void complete_notOwner_400() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        long articleId = createArticle("done-notmine", "5.00", 5);
        long orderId = prepareOrder(a, articleId, 1);

        mockMvc.perform(put("/api/orders/" + orderId + "/complete")
                        .param("testUserId", String.valueOf(b.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Non sei autorizzato a completare questo ordine"));
    }
}
