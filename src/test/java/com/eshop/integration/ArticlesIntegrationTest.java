package com.eshop.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4 — Integrazione full-stack: {@code /api/articles} (+ {@code /api/categories}).
 *
 * <p>Authorization (verificata nel full context, probe C): POST/PUT/DELETE con
 * {@code @PreAuthorize("hasRole('ADMIN')")} → 403 per USER e anonimi (testati in
 * {@link AuthIntegrationTest}), qui il focus è su CRUD, filtri, paginazione e validazione.
 */
class ArticlesIntegrationTest extends IntegrationTestSupport {

    private String searchToken() {
        return "s4tok" + System.nanoTime();
    }

    // ==================== CREATE ====================

    @Test
    void create_asAdmin_201_persistedWithAuthor() throws Exception {
        Auth admin = admin();
        String name = searchToken() + "-art";

        MvcResult res = mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", name,
                                "description", "desc",
                                "price", new java.math.BigDecimal("12.50"),
                                "stock", 7))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.price").value(12.50))
                .andExpect(jsonPath("$.stock").value(7))
                .andExpect(jsonPath("$.authorUsername").value(admin.username()))
                .andReturn();

        long id = readJson(res.getResponse().getContentAsString()).path("id").asLong();
        assertThat(id).isPositive();
        assertThat(stockOf(id)).isEqualTo(7);
    }

    @Test
    void create_negativePrice_400() throws Exception {
        mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + admin().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "neg-price",
                                "price", new java.math.BigDecimal("-1"),
                                "stock", 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Il prezzo deve essere positivo"));
    }

    @Test
    void create_negativeStock_400() throws Exception {
        mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + admin().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "neg-stock",
                                "price", new java.math.BigDecimal("10"),
                                "stock", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Lo stock non può essere negativo"));
    }

    @Test
    void create_missingName_400_validation() throws Exception {
        mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + admin().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "price", new java.math.BigDecimal("10"),
                                "stock", 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").exists());
    }

    // ==================== READ / FILTRI / PAGINAZIONE ====================

    @Test
    void findAll_searchAndPagination_shape() throws Exception {
        String token = searchToken();
        createArticle(token + "-alpha", "5.00", 1);
        createArticle(token + "-beta", "10.00", 2);
        createArticle(token + "-gamma", "20.00", 3);

        // search + pagina size=2 → solo i 3 articoli del token, paginazione coerente
        mockMvc.perform(get("/api/articles")
                        .param("search", token)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").isString());

        // pagina 1 → ultimo elemento
        mockMvc.perform(get("/api/articles")
                        .param("search", token)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void findAll_priceFilters() throws Exception {
        // Range "impossibile" per altri test: il DB è condiviso nel JVM, quindi
        // filtri globali (senza search) devono usare prezzi unici.
        String token = searchToken();
        createArticle(token + "-low", "6005.00", 1);
        createArticle(token + "-mid", "6010.00", 2);
        createArticle(token + "-high", "6020.00", 3);

        // solo i filtri → path findByFilters: nel range [6006, 6015] solo "-mid"
        mockMvc.perform(get("/api/articles")
                        .param("minPrice", "6006")
                        .param("maxPrice", "6015"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // search + filtri → path findBySearchAndFilters
        String combinedJson = mockMvc.perform(get("/api/articles")
                        .param("search", token)
                        .param("minPrice", "6006")
                        .param("maxPrice", "6015"))
                .andReturn().getResponse().getContentAsString();
        JsonNode combined = readJson(combinedJson);
        assertThat(combined.path("totalElements").asInt()).isEqualTo(1);
        assertThat(combined.path("content").get(0).path("name").asText())
                .isEqualTo(token + "-mid");
    }

    @Test
    void findAll_categoryFilter() throws Exception {
        String category = "cat-" + searchToken();
        createArticleWithCategory(searchToken() + "-cated", category, "15.00", 4);

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray());

        mockMvc.perform(get("/api/articles").param("category", category))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].category").value(category));
    }

    @Test
    void categories_endpoint_containsNewCategory() throws Exception {
        String category = "cat-" + searchToken();
        createArticleWithCategory(searchToken() + "-cated2", category, "9.00", 2);

        String body = mockMvc.perform(get("/api/categories")).andReturn()
                .getResponse().getContentAsString();
        assertThat(readJson(body).path("categories").toString()).contains(category);
    }

    @Test
    void findById_200() throws Exception {
        long id = createArticle(searchToken() + "-byid", "8.00", 3);
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.price").value(8.00));
    }

    @Test
    void findById_unknown_404() throws Exception {
        mockMvc.perform(get("/api/articles/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Articolo non trovato: 999999999"));
    }

    @Test
    void findByAuthor_returnsOwnArticles() throws Exception {
        Auth adminAuth = admin();
        String token = searchToken();
        createArticle(token + "-authored", "4.00", 1);

        mockMvc.perform(get("/api/articles/by-author/" + adminAuth.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        // l'articolo creato dal questo admin è nella lista (filtro per nome nel corpo)
        MvcResult res = mockMvc.perform(get("/api/articles/by-author/" + adminAuth.id())).andReturn();
        assertThat(readJson(res.getResponse().getContentAsString()).toString())
                .contains(token + "-authored");
    }

    // ==================== UPDATE / DELETE ====================

    @Test
    void update_asAdmin_200_priceAndStockChanged() throws Exception {
        long id = createArticle(searchToken() + "-upd", "10.00", 10);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "upd-name");
        body.put("price", new java.math.BigDecimal("15.75"));
        body.put("stock", 42);
        mockMvc.perform(put("/api/articles/" + id)
                        .header("Authorization", "Bearer " + admin().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(15.75))
                .andExpect(jsonPath("$.stock").value(42));

        assertThat(stockOf(id)).isEqualTo(42);
        assertThat(articlesRepository.findById(id).orElseThrow().getPrice())
                .isEqualByComparingTo(new java.math.BigDecimal("15.75"));
    }

    @Test
    void update_missingArticle_404() throws Exception {
        mockMvc.perform(put("/api/articles/999999999")
                        .header("Authorization", "Bearer " + admin().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "x",
                                "price", new java.math.BigDecimal("1"),
                                "stock", 1))))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asAdmin_204_thenNotFound() throws Exception {
        long id = createArticle(searchToken() + "-del", "3.00", 2);

        mockMvc.perform(delete("/api/articles/" + id)
                        .header("Authorization", "Bearer " + admin().accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isNotFound());
    }
}
