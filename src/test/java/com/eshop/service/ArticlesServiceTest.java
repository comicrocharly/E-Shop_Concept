package com.eshop.service;

import com.eshop.dto.CreateArticlesRequest;
import com.eshop.entity.Articles;
import com.eshop.entity.User;
import com.eshop.repository.ArticlesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 — Unit tests for {@link ArticlesService} (mocked repository).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticlesServiceTest {

    @Mock
    private ArticlesRepository articlesRepository;

    @InjectMocks
    private ArticlesService articlesService;

    private User author;

    @BeforeEach
    void setUp() {
        author = User.builder()
                .id(1L).username("admin").email("admin@test.local")
                .password("h").role("ADMIN").build();
    }

    private Articles article(long id) {
        return Articles.builder()
                .id(id)
                .name("Macchina da caffè")
                .description("desc")
                .price(new BigDecimal("49.90"))
                .stock(5)
                .author(author)
                .build();
    }

    // ==================== READ ====================

    @Nested
    @DisplayName("read operations")
    class Read {

        @Test
        @DisplayName("findById returns the article")
        void findByIdFound() {
            when(articlesRepository.findById(1L)).thenReturn(Optional.of(article(1L)));

            assertThat(articlesService.findById(1L).getName()).isEqualTo("Macchina da caffè");
        }

        @Test
        @DisplayName("findById missing -> IllegalArgumentException")
        void findByIdMissing() {
            when(articlesRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> articlesService.findById(404L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Articolo non trovato");
        }

        @Test
        @DisplayName("delegates: findAll, findBySearch, filters, categories, byAuthor, exists")
        void readDelegation() {
            when(articlesRepository.findAll()).thenReturn(List.of(article(1L)));
            when(articlesRepository.findDistinctCategories()).thenReturn(Set.of("Cucina"));
            when(articlesRepository.findByAuthorId(1L)).thenReturn(List.of(article(1L)));
            when(articlesRepository.existsById(1L)).thenReturn(true);

            assertThat(articlesService.findAll()).hasSize(1);
            assertThat(articlesService.findDistinctCategories()).containsExactly("Cucina");
            assertThat(articlesService.findByAuthorId(1L)).hasSize(1);
            assertThat(articlesService.existsById(1L)).isTrue();
        }
    }

    // ==================== CREATE ====================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates article with author")
        void createSuccess() {
            CreateArticlesRequest request = new CreateArticlesRequest(
                    "Macchina da caffè", "desc", new BigDecimal("49.90"), 5);
            when(articlesRepository.save(any(Articles.class))).thenAnswer(inv -> {
                Articles a = inv.getArgument(0);
                a.setId(1L);
                return a;
            });

            Articles saved = articlesService.create(request, author);

            assertThat(saved.getId()).isEqualTo(1L);
            assertThat(saved.getName()).isEqualTo("Macchina da caffè");
            assertThat(saved.getPrice()).isEqualByComparingTo("49.90");
            assertThat(saved.getStock()).isEqualTo(5);
            assertThat(saved.getAuthor()).isSameAs(author);
        }

        @Test
        @DisplayName("zero price -> IllegalArgumentException")
        void createZeroPrice() {
            CreateArticlesRequest request = new CreateArticlesRequest("X", null, BigDecimal.ZERO, 1);

            assertThatThrownBy(() -> articlesService.create(request, author))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("prezzo");
            verify(articlesRepository, never()).save(any(Articles.class));
        }

        @Test
        @DisplayName("negative price -> IllegalArgumentException")
        void createNegativePrice() {
            CreateArticlesRequest request = new CreateArticlesRequest("X", null, new BigDecimal("-1"), 1);

            assertThatThrownBy(() -> articlesService.create(request, author))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("prezzo");
        }

        @Test
        @DisplayName("negative stock -> IllegalArgumentException")
        void createNegativeStock() {
            CreateArticlesRequest request = new CreateArticlesRequest("X", null, new BigDecimal("10"), -1);

            assertThatThrownBy(() -> articlesService.create(request, author))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stock");
        }
    }

    // ==================== UPDATE / DELETE ====================

    @Nested
    @DisplayName("update / delete")
    class UpdateDelete {

        @Test
        @DisplayName("update replaces name, description, price, stock")
        void updateSuccess() {
            when(articlesRepository.findById(1L)).thenReturn(Optional.of(article(1L)));
            when(articlesRepository.save(any(Articles.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateArticlesRequest request = new CreateArticlesRequest(
                    "Nuovo nome", "nuova desc", new BigDecimal("99.90"), 12);
            Articles updated = articlesService.update(1L, request);

            assertThat(updated.getName()).isEqualTo("Nuovo nome");
            assertThat(updated.getDescription()).isEqualTo("nuova desc");
            assertThat(updated.getPrice()).isEqualByComparingTo("99.90");
            assertThat(updated.getStock()).isEqualTo(12);
        }

        @Test
        @DisplayName("update missing article -> IllegalArgumentException")
        void updateMissing() {
            when(articlesRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> articlesService.update(404L,
                    new CreateArticlesRequest("X", null, BigDecimal.ONE, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(articlesRepository, never()).save(any(Articles.class));
        }

        @Test
        @DisplayName("delete existing article")
        void deleteExisting() {
            when(articlesRepository.existsById(1L)).thenReturn(true);

            articlesService.delete(1L);

            verify(articlesRepository).deleteById(1L);
        }

        @Test
        @DisplayName("delete missing article -> IllegalArgumentException")
        void deleteMissing() {
            when(articlesRepository.existsById(404L)).thenReturn(false);

            assertThatThrownBy(() -> articlesService.delete(404L))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(articlesRepository, never()).deleteById(404L);
        }
    }
}
