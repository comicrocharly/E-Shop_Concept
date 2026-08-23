package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.CurrentUser;
import com.eshop.config.JwtAuthenticationFilter;
import com.eshop.config.RateLimitFilter;
import com.eshop.controller.ControllerTestSupport.MethodSecurityConfig;
import com.eshop.controller.ControllerTestSupport.TestFixtures;
import com.eshop.dto.CreateArticlesRequest;
import com.eshop.entity.Articles;
import com.eshop.entity.User;
import com.eshop.service.ArticlesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 — unit test {@code @WebMvcTest} di {@link ArticlesController} (service mockati).
 */
@WebMvcTest(value = ArticlesController.class, properties = "app.security.allow-test-userid=true")
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class ArticlesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ArticlesService articlesService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    private static final String VALID_BODY =
            "{\"name\":\"PS4\",\"description\":\"console\",\"price\":399.99,\"stock\":10}";

    // ==================== GET LIST ====================

    @Test
    void findAll_noParams_returnsPage() throws Exception {
        when(articlesService.findAll(any(Pageable.class)))
                .thenReturn(TestFixtures.page(
                        TestFixtures.articles(1L, "PS4", new BigDecimal("399.99"), 10),
                        TestFixtures.articles(2L, "Xbox", new BigDecimal("429.99"), 5)));

        mockMvc.perform(get("/api/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("PS4"));
    }

    @Test
    void findAll_withSearch_usesFindBySearch() throws Exception {
        when(articlesService.findBySearch(eq("ps4"), any(Pageable.class)))
                .thenReturn(TestFixtures.page(
                        TestFixtures.articles(1L, "PS4", new BigDecimal("399.99"), 10)));

        mockMvc.perform(get("/api/articles").param("search", "ps4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("PS4"));

        verify(articlesService).findBySearch(eq("ps4"), any(Pageable.class));
    }

    @Test
    void findAll_withCategory_usesFindByFilters() throws Exception {
        when(articlesService.findByFilters(eq("Electronics"), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(TestFixtures.page(
                        TestFixtures.articles(1L, "PS4", new BigDecimal("399.99"), 10)));

        mockMvc.perform(get("/api/articles").param("category", "Electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(articlesService).findByFilters(eq("Electronics"), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    void findAll_withSearchAndPriceRange_usesFindBySearchAndFilters() throws Exception {
        when(articlesService.findBySearchAndFilters(eq("ps4"), eq(null), eq(new BigDecimal("10")), eq(null), any(Pageable.class)))
                .thenReturn(TestFixtures.page(
                        TestFixtures.articles(1L, "PS4", new BigDecimal("399.99"), 10)));

        mockMvc.perform(get("/api/articles").param("search", "ps4").param("minPrice", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(articlesService)
                .findBySearchAndFilters(eq("ps4"), eq(null), eq(new BigDecimal("10")), eq(null), any(Pageable.class));
    }

    // ==================== GET BY ID / AUTHOR ====================

    @Test
    void findById_success_returns200() throws Exception {
        when(articlesService.findById(5L))
                .thenReturn(TestFixtures.articles(5L, "PS4", new BigDecimal("399.99"), 10));

        mockMvc.perform(get("/api/articles/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("PS4"));
    }

    @Test
    void findById_notFound_returns404() throws Exception {
        when(articlesService.findById(99L))
                .thenThrow(new IllegalArgumentException("Articolo non trovato: 99"));

        mockMvc.perform(get("/api/articles/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Articolo non trovato: 99"));
    }

    @Test
    void findByAuthor_success_returns200() throws Exception {
        when(articlesService.findByAuthorId(1L))
                .thenReturn(java.util.List.of(
                        TestFixtures.articles(10L, "Articolo A", new BigDecimal("10.00"), 3)));

        mockMvc.perform(get("/api/articles/by-author/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Articolo A"));
    }

    // ==================== POST (admin-only) ====================

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void create_success_returns201() throws Exception {
        User author = TestFixtures.user(1L, "admin", true);
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(currentUser.getCurrentUser()).thenReturn(author);
        when(articlesService.create(any(CreateArticlesRequest.class), eq(author)))
                .thenReturn(TestFixtures.articles(11L, "PS4", new BigDecimal("399.99"), 10));

        mockMvc.perform(post("/api/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("PS4"))
                .andExpect(jsonPath("$.price").value(399.99));
    }

    @WithMockUser(username = "bob", roles = "USER")
    @Test
    void create_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));

        verify(articlesService, org.mockito.Mockito.never()).create(any(), any());
    }

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void create_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/api/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.price").exists())
                .andExpect(jsonPath("$.stock").exists());

        verify(articlesService, org.mockito.Mockito.never()).create(any(), any());
    }

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void create_negativePrice_returns400() throws Exception {
        User author = TestFixtures.user(1L, "admin", true);
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(currentUser.getCurrentUser()).thenReturn(author);
        when(articlesService.create(any(CreateArticlesRequest.class), eq(author)))
                .thenThrow(new IllegalArgumentException("Il prezzo deve essere positivo"));

        mockMvc.perform(post("/api/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"description\":\"d\",\"price\":-1,\"stock\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Il prezzo deve essere positivo"));
    }

    // ==================== PUT (admin-only) ====================

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void update_success_returns200() throws Exception {
        when(articlesService.update(eq(5L), any(CreateArticlesRequest.class)))
                .thenReturn(TestFixtures.articles(5L, "PS4 Pro", new BigDecimal("449.99"), 8));

        mockMvc.perform(put("/api/articles/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("PS4 Pro"));
    }

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void update_notFound_returns404() throws Exception {
        when(articlesService.update(eq(99L), any(CreateArticlesRequest.class)))
                .thenThrow(new IllegalArgumentException("Articolo non trovato: 99"));

        mockMvc.perform(put("/api/articles/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Articolo non trovato: 99"));
    }

    @WithMockUser(username = "bob", roles = "USER")
    @Test
    void update_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/articles/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verify(articlesService, org.mockito.Mockito.never()).update(any(), any());
    }

    // ==================== DELETE (admin-only) ====================

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void delete_success_returns204() throws Exception {
        mockMvc.perform(delete("/api/articles/5"))
                .andExpect(status().isNoContent());

        verify(articlesService).delete(5L);
    }

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void delete_notFound_returns404() throws Exception {
        doThrow(new IllegalArgumentException("Articolo non trovato: 99"))
                .when(articlesService).delete(99L);

        mockMvc.perform(delete("/api/articles/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Articolo non trovato: 99"));
    }
}
