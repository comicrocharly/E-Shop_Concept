package com.eshop.integration;

import com.eshop.AbstractIntegrationTest;
import com.eshop.config.JwtTokenProvider;
import com.eshop.entity.Articles;
import com.eshop.entity.User;
import com.eshop.repository.ArticlesRepository;
import com.eshop.repository.CartRepository;
import com.eshop.repository.OrderPaymentRepository;
import com.eshop.repository.OrderRepository;
import com.eshop.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Support condiviso per i test di integrazione full-stack (S4).
 *
 * <p>Estende {@link AbstractIntegrationTest} (contesto completo, profile {@code test},
 * Testcontainers PostgreSQL, rate-limit alzati) e aggiunge:
 * <ul>
 *   <li>{@code @AutoConfigureMockMvc} (MockMvc con la security chain del contesto);</li>
 *   <li>utenti con username univoci per chiamata (il DB è condiviso tra le classi nel
 *       stesso JVM: i test non possono fare affidamento su dati "puliti");</li>
 *   <li>{@link #admin()} — utente ADMIN creato via JPA (il registro API crea solo USER)
 *       e autenticato via {@code /api/auth/login} (JWT reale, role claim ADMIN);</li>
 *   <li>helper API e repository per setup/assert deterministici.</li>
 * </ul>
 *
 * <p>Nota sugli auth path nel full context (verificato da ScratchFilterProbeTest):
 * <ul>
 *   <li>{@code ?testUser=username} → SecurityTestConfig.TestAuthFilter → SecurityContext (sempre ROLE_USER);</li>
 *   <li>Bearer JWT → JwtAuthenticationFilter (attivo nel contesto test: il claim "role"
 *       del token determina ADMIN/USER);</li>
 *   <li>gli endpoint con parametro {@code ?testUserId=<id>} usano quell'ID direttamente
 *       (bypass del SecurityContext).</li>
 * </ul>
 */
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport extends AbstractIntegrationTest {

    /** Sessione autenticata: dati user + JWT reali ottenuti via API. */
    protected record Auth(Long id, String username, String role, String accessToken, String refreshToken) {}

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected ArticlesRepository articlesRepository;
    @Autowired
    protected OrderRepository orderRepository;
    @Autowired
    protected OrderPaymentRepository orderPaymentRepository;
    @Autowired
    protected CartRepository cartRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    private Auth cachedAdmin;

    // ==================== helper generici ====================

    protected static String unique(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    protected JsonNode readJson(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    // ==================== auth ====================

    /** Registra un utente USER via API. Ritorna l'username (univoco per chiamata). */
    protected String register(String password) throws Exception {
        String username = unique("u");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", password,
                                "email", username + "@int.test"))))
                .andExpect(status().isCreated());
        return username;
    }

    /** Login via API: ritorna i JWT reali e i metadati user. */
    protected Auth login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = readJson(res.getResponse().getContentAsString());
        return new Auth(
                body.path("user").path("id").asLong(),
                body.path("user").path("username").asText(),
                body.path("user").path("role").asText(),
                body.path("accessToken").asText(),
                body.path("refreshToken").asText());
    }

    /**
     * Utente ADMIN: il registro API crea solo utenti USER e non esiste endpoint di
     * promozione, quindi l'admin è creato via JPA (password BCrypt) e poi autenticato
     * via API. Cache per istanza di test (l'utente è univoco per contesto).
     */
    protected Auth admin() throws Exception {
        if (cachedAdmin == null) {
            String username = unique("admin");
            User admin = User.builder()
                    .username(username)
                    .password(passwordEncoder.encode("admin-secret-123"))
                    .email(username + "@admin.test")
                    .role("ADMIN")
                    .build();
            userRepository.save(admin);
            cachedAdmin = login(username, "admin-secret-123");
        }
        return cachedAdmin;
    }

    // ==================== articoli ====================

    /** Crea un articolo via API come ADMIN (endpoint {@code POST /api/articles}). */
    protected long createArticle(String name, String price, int stock) throws Exception {
        Auth a = admin();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("price", new BigDecimal(price));
        body.put("stock", stock);
        MvcResult res = mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return readJson(res.getResponse().getContentAsString()).path("id").asLong();
    }

    /**
     * Crea un articolo via JPA con categoria (l'API di creazione non espone la
     * categoria: {@code CreateArticlesRequest} non ha il campo). Author = admin.
     */
    protected long createArticleWithCategory(String name, String category, String price, int stock) throws Exception {
        Auth a = admin();
        User author = userRepository.findByUsername(a.username()).orElseThrow();
        Articles article = Articles.builder()
                .name(name)
                .category(category)
                .price(new BigDecimal(price))
                .stock(stock)
                .author(author)
                .build();
        return articlesRepository.save(article).getId();
    }

    protected int stockOf(long articleId) {
        return articlesRepository.findById(articleId).orElseThrow().getStock();
    }

    protected void setStock(long articleId, int stock) {
        Articles a = articlesRepository.findById(articleId).orElseThrow();
        a.setStock(stock);
        articlesRepository.save(a);
    }

    protected void updatePrice(long articleId, String price) throws Exception {
        Auth a = admin();
        Articles current = articlesRepository.findById(articleId).orElseThrow();
        Map<String, Object> body = Map.of(
                "name", current.getName(),
                "price", new BigDecimal(price),
                "stock", current.getStock());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/articles/" + articleId)
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }
}
