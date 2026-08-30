package com.eshop.service;

import com.eshop.dto.CreateArticlesRequest;
import com.eshop.entity.ArticleImage;
import com.eshop.entity.Articles;
import com.eshop.entity.User;
import com.eshop.repository.ArticleImageRepository;
import com.eshop.repository.ArticlesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArticlesService {

    private final ArticlesRepository articlesRepository;
    private final ArticleImageRepository articleImageRepository;

    /** Cartella fisica dove vivono i file immagine */
    @Value("${app.article-image-dir:data/article-images}")
    private Path storageDir;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    @Transactional(readOnly = true)
    public List<Articles> findAll() {
        return articlesRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Page<Articles> findAll(Pageable pageable) {
        return articlesRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Articles> findBySearch(String search, Pageable pageable) {
        return articlesRepository.findBySearch(search, pageable);
    }

    // --- Category + Price Filters ---

    @Transactional(readOnly = true)
    public Set<String> findDistinctCategories() {
        return articlesRepository.findDistinctCategories();
    }

    @Transactional(readOnly = true)
    public Page<Articles> findByFilters(String category, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return articlesRepository.findByFilters(category, minPrice, maxPrice, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Articles> findBySearchAndFilters(String search, String category, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return articlesRepository.findBySearchAndFilters(search, category, minPrice, maxPrice, pageable);
    }

    @Transactional(readOnly = true)
    public Articles findById(Long id) {
        return articlesRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Articolo non trovato: " + id));
    }

    @Transactional(readOnly = true)
    public List<Articles> findByAuthorId(Long authorId) {
        return articlesRepository.findByAuthorId(authorId);
    }

    @Transactional
    public Articles create(CreateArticlesRequest request, User author) {
        validateRequest(request);

        Articles articles = Articles.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stock(request.stock())
                .author(author)
                .build();
        return articlesRepository.save(articles);
    }

    /** Crea un articolo con le sue immagini in un'unica transazione.
     *  (Necessario: con OSIV lo stesso persistence context è condiviso nella stessa
     *  richiesta e un findById successivo restituisce l'istanza creata con la
     *  collezione immagini non ancora inizializzata.) */
    @Transactional
    public Articles createWithImages(String name, String description, BigDecimal price, Integer stock,
                                     User author, MultipartFile[] images) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Il prezzo deve essere positivo");
        }
        if (stock == null || stock < 0) {
            throw new IllegalArgumentException("Lo stock non può essere negativo");
        }

        Articles article = Articles.builder()
                .name(name)
                .description(description)
                .price(price)
                .stock(stock)
                .author(author)
                .build();

        if (images != null) {
            int position = 0;
            for (MultipartFile file : images) {
                if (file == null || file.isEmpty()) continue;
                String fileName = storeFile(file);
                article.getImages().add(ArticleImage.builder()
                        .article(article)
                        .fileName(fileName)
                        .position(position++)
                        .build());
            }
        }

        return articlesRepository.save(article);
    }

    @Transactional
    public Articles update(Long id, CreateArticlesRequest request) {
        Articles existing = findById(id);
        existing.setName(request.name());
        existing.setDescription(request.description());
        existing.setPrice(request.price());
        existing.setStock(request.stock());
        return articlesRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!articlesRepository.existsById(id)) {
            throw new IllegalArgumentException("Articolo non trovato: " + id);
        }
        Articles article = findById(id);
        articleImageRepository.deleteAll(article.getImages());
        article.getImages().forEach(img -> deleteFileQuietly(img.getFileName()));
        articlesRepository.deleteById(id);
    }

    // --- Gestione immagini ---

    /**
     * Aggiunge una o più immagini a un articolo esistente.
     */
    @Transactional
    public Articles addImages(Long articleId, MultipartFile[] files) {
        Articles article = findById(articleId);
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Nessuna immagine allegata");
        }
        if (files.length > 10) {
            throw new IllegalArgumentException("Massimo 10 immagini per articolo");
        }
        int position = article.getImages().size();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String fileName = storeFile(file);
            ArticleImage image = ArticleImage.builder()
                    .article(article)
                    .fileName(fileName)
                    .position(position++)
                    .build();
            articleImageRepository.save(image);
            article.getImages().add(image);
        }
        return article;
    }

    /**
     * Rimuove un'immagine (riga + file su disk).
     */
    @Transactional
    public void removeImage(Long imageId) {
        ArticleImage image = articleImageRepository.findById(imageId)
                .orElseThrow(() -> new IllegalArgumentException("Immagine non trovata: " + imageId));
        articleImageRepository.delete(image);
        deleteFileQuietly(image.getFileName());
    }

    private String storeFile(MultipartFile file) {
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Formato immagine non supportato: " + ext + " (ammessi: png, jpg, webp, gif)");
        }
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Files.createDirectories(storageDir);
            file.transferTo(storageDir.resolve(fileName));
            return fileName;
        } catch (IOException e) {
            throw new UncheckedIOException("Errore nel salvataggio dell'immagine", e);
        }
    }

    private void deleteFileQuietly(String fileName) {
        if (fileName == null) {
            return;
        }
        try {
            Files.deleteIfExists(storageDir.resolve(fileName));
        } catch (IOException ignored) {
            // il file è già sparito o non esiste: non blocca l'operazione
        }
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return articlesRepository.existsById(id);
    }

    private void validateRequest(CreateArticlesRequest request) {
        if (request.price().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Il prezzo deve essere positivo");
        }
        if (request.stock() < 0) {
            throw new IllegalArgumentException("Lo stock non può essere negativo");
        }
    }
}
