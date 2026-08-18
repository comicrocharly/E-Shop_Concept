package com.eshop.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4 — Integrazione full-stack: {@code /api/cart}.
 *
 * <p>La risposta degli endpoint è l'entità {@code Cart} serializzata:
 * {@code {id, user:{...}, items:[{id, articles:{...}, quantity, unitPrice, subtotal}]}}
 * (la ricorsione Cart↔User è rotta da {@code @JsonIgnoreProperties} su {@code Cart.user}).
 *
 * <p>Param di routing utente: {@code ?testUserId=<id numerico>} (bypass del
 * SecurityContext, come nei controller "S3 legacy").
 */
class CartIntegrationTest extends IntegrationTestSupport {

    private static final String PW = "secret123";

    private Auth newUser() throws Exception {
        return login(register(PW), PW);
    }

    private String add(long userId, long articleId, int qty) throws Exception {
        return mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", articleId, "quantity", qty))))
                .andReturn().getResponse().getContentAsString();
    }

    private String myCart(long userId) throws Exception {
        return mockMvc.perform(get("/api/cart/me").param("testUserId", String.valueOf(userId)))
                .andReturn().getResponse().getContentAsString();
    }

    private String cartTotal(long userId) throws Exception {
        return mockMvc.perform(get("/api/cart/total").param("testUserId", String.valueOf(userId)))
                .andReturn().getResponse().getContentAsString();
    }

    // ==================== LECTURA ====================

    @Test
    void getCart_emptyCart_200() throws Exception {
        Auth a = newUser();
        mockMvc.perform(get("/api/cart/me").param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.user.username").value(a.username()));
    }

    @Test
    void emptyCartTotal_0() throws Exception {
        Auth a = newUser();
        assertThat(cartTotal(a.id())).contains("0");
    }

    // ==================== ADD / TOTALE ====================

    @Test
    void addToCart_200_totalMatches() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("cart-art", "10.50", 10);

        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", articleId, "quantity", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].articles.id").value(articleId))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(10.50))
                .andExpect(jsonPath("$.items[0].subtotal").value(21.00));

        assertThat(cartTotal(a.id())).contains("21");
    }

    @Test
    void reAddSameArticle_incrementsQuantity() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("cart-art2", "5.00", 10);

        add(a.id(), articleId, 2);
        add(a.id(), articleId, 3);

        JsonNode cart = readJson(myCart(a.id()));
        assertThat(cart.path("items").size()).isEqualTo(1);
        assertThat(cart.path("items").get(0).path("quantity").asInt()).isEqualTo(5);
        assertThat(cart.path("items").get(0).path("subtotal").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("25.00"));
    }

    // ==================== PREZZO (S2 rule 2: listener @PreUpdate) ====================

    @Test
    void articlePriceChange_syncsCartItemOnNextCartSave() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("cart-priced", "10.00", 10);
        add(a.id(), articleId, 1);

        // cambio prezzo via API (admin)
        updatePrice(articleId, "20.00");

        // ⚠ Comportamento attuale: il listener JPA scatta solo al save della cart;
        // finché la cart non viene salvata, unitPrice resta il valore "al momento
        // dell'acquisto" (10) mentre articles.price mostra già il nuovo prezzo.
        JsonNode stale = readJson(myCart(a.id()));
        assertThat(stale.path("items").get(0).path("unitPrice").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("10.00"));
        assertThat(stale.path("items").get(0).path("articles").path("price").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("20.00"));

        // al prossimo save (re-add) il listener sincronizza unitPrice sul nuovo prezzo
        add(a.id(), articleId, 1);
        JsonNode synced = readJson(myCart(a.id()));
        assertThat(synced.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(synced.path("items").get(0).path("unitPrice").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("20.00"));
        assertThat(cartTotal(a.id())).contains("40");
    }

    // ==================== REMOVE / CLEAR ====================

    @Test
    void removeFromCart_200() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("cart-rem", "7.00", 10);
        add(a.id(), articleId, 1);

        // ⚠ Nota: il path parameter dell'endpoint è l'ARTICLE id (non l'item id);
        // rimuovere un "articolo" che non è in cart è un no-op silenzioso (200).
        mockMvc.perform(delete("/api/cart/items/" + articleId)
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        assertThat(cartTotal(a.id())).contains("0");
    }

    @Test
    void clearCart_200() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("cart-clear", "7.00", 10);
        add(a.id(), articleId, 3);

        mockMvc.perform(delete("/api/cart/clear")
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        assertThat(cartTotal(a.id())).contains("0");
    }

    // ==================== ERRORI ====================

    @Test
    void addInsufficientStock_409() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("cart-lowstock", "3.00", 10);
        add(a.id(), articleId, 9);          // stock residuo 1
        setStock(articleId, 0);            // simula esaurimento esterno

        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", articleId, "quantity", 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Stock insufficiente per 'cart-lowstock'. Disponibile: 0, Richiesto: 5"));
    }

    @Test
    void addUnknownArticle_404() throws Exception {
        Auth a = newUser();
        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", 999999999L, "quantity", 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Articolo non trovato: 999999999"));
    }

    @Test
    void addZeroQuantity_400_validation() throws Exception {
        Auth a = newUser();
        long articleId = createArticle("cart-val", "3.00", 10);
        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("articleId", articleId, "quantity", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.quantity").exists());
    }

    @Test
    void addMissingFields_400_validation() throws Exception {
        Auth a = newUser();
        mockMvc.perform(post("/api/cart/items")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.articleId").exists())
                // ⚠ Comportamento attuale: quantity=null NON scatta (solo @Positive,
                // niente @NotNull) → nella risposta c'è solo il campo articleId.
                .andExpect(jsonPath("$.quantity").doesNotExist());
    }

    // ==================== ISOLAMENTO ====================

    @Test
    void userBCannotSeeUserACart() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        long articleId = createArticle("cart-iso", "4.00", 10);
        add(a.id(), articleId, 2);

        // B vede il proprio cart (vuoto), non quello di A
        JsonNode bCart = readJson(myCart(b.id()));
        assertThat(bCart.path("items").size()).isZero();
        assertThat(bCart.path("user").path("username").asText()).isEqualTo(b.username());
        assertThat(cartTotal(b.id())).contains("0");

        // A ha ancora il suo
        assertThat(readJson(myCart(a.id())).path("items").size()).isEqualTo(1);
        assertThat(cartTotal(a.id())).contains("8");
    }
}
