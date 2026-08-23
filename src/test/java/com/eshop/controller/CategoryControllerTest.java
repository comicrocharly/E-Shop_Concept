package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.JwtAuthenticationFilter;
import com.eshop.config.RateLimitFilter;
import com.eshop.controller.ControllerTestSupport.MethodSecurityConfig;
import com.eshop.service.ArticlesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 — unit test {@code @WebMvcTest} di {@link CategoryController} (service mockato).
 */
@WebMvcTest(value = CategoryController.class, properties = "app.security.allow-test-userid=true")
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ArticlesService articlesService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    @Test
    void getCategories_success_returnsCategorySet() throws Exception {
        when(articlesService.findDistinctCategories())
                .thenReturn(Set.of("Electronics", "Gaming"));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(2))
                // ⚠ Set: l'ordine non è garantito → assertion "in any order"
                .andExpect(jsonPath("$.categories", org.hamcrest.Matchers.containsInAnyOrder("Electronics", "Gaming")));
    }

    @Test
    void getCategories_empty_returnsEmptyArray() throws Exception {
        when(articlesService.findDistinctCategories()).thenReturn(Set.of());

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void getCategories_multiple_returnsArrayOfStrings() throws Exception {
        when(articlesService.findDistinctCategories())
                .thenReturn(Set.of("A", "B", "C"));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(3))
                .andExpect(jsonPath("$.categories", org.hamcrest.Matchers.containsInAnyOrder("A", "B", "C")));
    }
}
