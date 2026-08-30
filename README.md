# 🛒 E-Shop — Spring Boot REST API

Un e-commerce RESTful completo costruito con **Spring Boot 3.x**, con autenticazione, gestione prodotti, carrello e ordini.

---

## 📋 Panoramica

E-Shop è un'applicazione backend REST completa che gestisce un intero flusso e-commerce: dalla registrazione utente alla creazione di ordini, passando per la gestione del catalogo e del carrello. Include anche un'interfaccia frontend in HTML/CSS/JS per testare e utilizzare l'applicazione direttamente dal browser.

---

## 🛠️ Tech Stack

| Livello | Tecnologia | Versione |
|---------|-----------|----------|
| **Runtime** | Java | 21 |
| **Framework** | Spring Boot 3.x | 3.4.1 |
| **Data Access** | Spring Data JPA | — |
| **Database** | PostgreSQL | 16 (Docker) |
| **Sicurezza** | Spring Security | JWT + Role-based |
| **Validazione** | Jakarta Validation | Bean Validation 3.0 |
| **Testing** | Testcontainers | 1.21.4 + JUnit 5 |
| **Build** | Maven | 3.x |
| **Frontend** | HTML5 / CSS3 / Vanilla JS | — |

---

## 🏗️ Architettura

L'applicazione segue l'architettura a layer tipica di Spring Boot:

```
┌─────────────────────────────────────────────┐
│              Frontend (index.html)           │
│     HTML5 + CSS3 + Vanilla JavaScript        │
└────────────────────┬────────────────────────┘
                     │  REST (JSON)
┌────────────────────▼────────────────────────┐
│              Controllers (REST API, 8)       │
│  Auth │ Articles │ Category │ Cart │ Order   │
│  User │ PhoneNumber │ Address               │
└────────────────────┬────────────────────────┘
                     │
┌────────────────────▼────────────────────────┐
│                Services (Logic)              │
│  UserService │ ArticlesService │ CartService │
│  OrderService │ PhoneNumberService │ AddressService │
│  JwtTokenProvider │ PaymentGatewayService   │
│  (MockPaymentGateway)                     │
└────────────────────┬────────────────────────┘
                     │
┌────────────────────▼────────────────────────┐
│            Repositories (JPA, 7)             │
│  User │ Articles │ Cart │ Order │ OrderPayment │
│  PhoneNumber │ Address                     │
└────────────────────┬────────────────────────┘
                     │
┌─────────────────────────────────────────────┐
│            Entities (JPA/Hibernate, 9)       │
│  User │ Articles │ Category │ Cart │ CartItem │
│  Order │ OrderItem │ OrderPayment │ PhoneNumber │
│  Address                                      │
└────────────────────┬────────────────────────┘
                     │
┌────────────────────▼────────────────────────┐
│            PostgreSQL Database (16)          │
└─────────────────────────────────────────────┘
```

Security by layer: Spring Security (JWT stateless + BCrypt + role-based
`@PreAuthorize`), `RateLimitFilter` su login/register (sliding window in-memory),
CORS per il frontend.

---

## 📊 Entity Relationship

```
User 1:1 Cart 1:N CartItem
User 1:N Order 1:N OrderItem, 1:1 OrderPayment (per ordine pagato)
User 1:N PhoneNumber, 1:N Address
Articles 1:N CartItem, OrderItem
Category (gerarchia parent) ↔ Articles
```

| Entity | Campi Principali |
|--------|-----------------|
| **User** | id, username, email, password (BCrypt), role (USER/ADMIN) |
| **Articles** | id, name, description, price, stock, category |
| **Category** | id, name, parent (gerarchia) |
| **Cart** | id, user (FK) |
| **CartItem** | id, cart (FK), article (FK), quantity, unitPrice (sync al prezzo corrente) |
| **Order** | id, user (FK), status, total, paymentMethod, reservedStock, orderDate |
| **OrderItem** | id, order (FK), article (FK), quantity, unitPrice (bloccato al checkout) |
| **OrderPayment** | id, order (FK), paymentMethod, amount, status, transactionId |
| **PhoneNumber** | id, user (FK), countryPrefix, number, phoneType (MOBILE/FIXED) |
| **Address** | id, user (FK), street, streetNumber, postalCode, city, country |

---

## 🔌 REST API Endpoints

### 🔐 Auth (stateless JWT)
| Metodo | Endpoint | Descrizione |
|--------|----------|-------------|
| `POST` | `/api/auth/register` | Registra un nuovo utente (rate limited 10/min) |
| `POST` | `/api/auth/login` | Login → access token (1h) + refresh token (24h) (rate limited 30/min) |
| `POST` | `/api/auth/refresh` | Renew access token con refresh token |

### 📦 Articles
| Metodo | Endpoint | Descrizione |
|--------|----------|-------------|
| `GET` | `/api/articles` | Lista prodotti (pagina, `search`, `category`, `minPrice`, `maxPrice`) |
| `GET` | `/api/articles/{id}` | Dettaglio prodotto |
| `GET` | `/api/articles/by-author/{authorId}` | Prodotti per autore |
| `POST` | `/api/articles` | Crea prodotto (ADMIN) |
| `PUT` | `/api/articles/{id}` | Aggiorna prodotto (ADMIN) |
| `DELETE` | `/api/articles/{id}` | Elimina prodotto (ADMIN) |
| `GET` | `/api/categories` | Lista categorie (gerarchia) |

### 🛒 Cart
| Metodo | Endpoint | Descrizione |
|--------|----------|-------------|
| `GET` | `/api/cart/me` | Leggi il proprio carrello |
| `POST` | `/api/cart/items` | Aggiungi al carrello (`{articleId, quantity}`) |
| `DELETE` | `/api/cart/items/{articleId}` | Rimuovi articolo dal carrello |
| `DELETE` | `/api/cart/clear` | Svuota carrello |
| `GET` | `/api/cart/total` | Calcola totale |

### 🧾 Orders (checkout 2-step + pagamento)
| Metodo | Endpoint | Descrizione |
|--------|----------|-------------|
| `POST` | `/api/orders/checkout/prepare` | Prepara ordine: PENDING, riserva stock, svuota carrello |
| `POST` | `/api/orders/{id}/pay` | Paga (MockPaymentGateway) → PROCESSING, deduce stock, crea OrderPayment |
| `POST` | `/api/orders/checkout` | Legacy checkout in 1 step (retrocompatibilità) |
| `GET` | `/api/orders/my` | Storico ordini utente (pagina + filtro `status`) |
| `GET` | `/api/orders/{id}` | Dettaglio ordine |
| `POST` | `/api/orders/{id}/cancel` | Annulla ordine |
| `PUT` | `/api/orders/{id}/complete` | Conferma ricezione (DELIVERED → COMPLETED) |
| `GET` | `/api/orders` | Tutti gli ordini (ADMIN) |
| `GET` | `/api/orders/admin` | Tutti gli ordini con utente/items (ADMIN, pagina+search+filter) |
| `PUT` | `/api/orders/{id}/status?status=X` | Aggiorna stato ordine (ADMIN) |

### 👤 User / Phone / Address
| Metodo | Endpoint | Descrizione |
|--------|----------|-------------|
| `GET` | `/api/users/me` | Profilo utente corrente |
| `PUT` | `/api/users/me/profile` | Aggiorna email / password (con `currentPassword`) |
| `GET` | `/api/users/{userId}/phone/me` | Lista telefoni |
| `POST` | `/api/users/{userId}/phone/me` | Aggiungi telefono |
| `DELETE` | `/api/users/{userId}/phone/me/{phoneId}` | Elimina telefono |
| `GET` | `/api/users/{userId}/address/me` | Lista indirizzi |
| `POST` | `/api/users/{userId}/address/me` | Aggiungi indirizzo |
| `DELETE` | `/api/users/{userId}/address/me/{addressId}` | Elimina indirizzo |

---

## ✨ Funzionalità

- ✅ **Registrazione e Login** — con validazione email e password (min 6 char)
- ✅ **JWT stateless** — access token (1h) + refresh token (24h), endpoint `/api/auth/refresh`
- ✅ **Password BCrypt** — hashate al registro, mai in chiaro
- ✅ **Rate limiting** — login 30/min, register 10/min (sliding window, headers X-RateLimit-*)
- ✅ **Ruoli utente** — USER e ADMIN con `@PreAuthorize` differenziato
- ✅ **Catalogo prodotti** — CRUD completo, stock, categorie (gerarchia), filtri/prezzi/ricerca
- ✅ **Carrello** — Aggiungi, rimuovi, svuota, calcolo totale; unitPrice sincronizzato al prezzo corrente
- ✅ **Ordini** — Checkout 2-step (prepare→pay) + legacy 1-step, stati PENDING→PROCESSING→SHIPPED→DELIVERED→COMPLETED/CANCELLED
- ✅ **Pagamento** — PaymentGatewayService + MockPaymentGateway (carta/PayPal/COD/bonifico), OrderPayment
- ✅ **Telefono e indirizzi** — CRUD per utente
- ✅ **Gestione errori globale** — Mapping errori HTTP (400/403/404/409/500)
- ✅ **Prevenzione cicli Jackson** — `@JsonIgnore` su relazioni bidirezionali
- ✅ **Frontend responsive** — HTML/CSS/JS con design moderno (modal pagamento, admin panel, settings)
- ✅ **Immagini articoli** — più immagini per articolo (tabella `article_images`), servite su `/images/articles/**`, con miniatura hover e lightbox navigabile nella scheda dettaglio

> **🖼️ Crediti immagini (frontend mockup)** — le immagini prodotto di questo progetto sono state generate con
> [**txt2mock_batchgen**](https://github.com/comicrocharly/txt2mock_batchgen), altro mio progetto di **generazione massiva di immagini mockup via ComfyUI API**:
> un LLM converte le righe della tabella `articles` in prompt English (`items.json`), e lo script trasforma il file in un batch di PNG (workflow **Z-Image Turbo**). Le immagini generate risiedono in `data/article-images/` e sono associate agli articoli.

---

## 🧪 Testing

Suite **rebuild** completata il 2026-08-17 (vedi `REBUILD_PLAN.md` per dettagli e note tecniche):
**302/302 test verdi** con `mvn test` (serve Docker per Testcontainers PostgreSQL 16).

| Sezione | Pacch. | Test | Descrizione |
|---------|--------|------|-------------|
| S0 | `com.eshop` | 2 | Smoke: contesto + round-trip DB |
| S1 | `com.eshop.config` | 12 | JwtTokenProvider (access/refresh, expired, tampered) |
| S2 | `com.eshop.service` | 113 | Services (Mockito) + transizioni OrderStatus |
| S3 | `com.eshop.controller` | 83 | `@WebMvcTest` per i 8 controller (services mockati) |
| S4 | `com.eshop.integration` | 92 | Full-stack `@SpringBootTest` + Testcontainers + MockMvc (Auth 19, Articles 14, Cart 12, Orders 28, User 18, Gateway 1) |

- Profilo test: `@ActiveProfiles("test")` → `SecurityTestConfig` (`permitAll` + param `?testUser=`), rate limit alzati in `application-test.properties`
- DB test: Testcontainers PostgreSQL 16 (`jdbc:tc:postgresql:16:///eshop`, `create-drop`, container condiviso per JVM)
- I bug noti dell'app (B1–B8) sono documentati da test che asseriscono il comportamento attuale (sezione §2.5 di `REBUILD_PLAN.md`)
- E2E browser (Playwright): pending (S5 del piano, `@Disabled` di default)

---

## 🚀 Avvio Rapido

### Requisiti
- Java 21
- Maven 3.x
- Docker (PostgreSQL 16 per l'app e Testcontainers per i test)

### Compilazione e avvio
```bash
cd eshop
mvn clean package -DskipTests
java -jar target/eshop-0.0.1-SNAPSHOT.jar
```

L'applicazione sarà disponibile su:
- **Frontend**: http://localhost:8081
- **API**: http://localhost:8081/api

---

## 📁 Struttura Progetto

```
eshop/
├── src/main/java/com/eshop/
│   ├── config/           # Security (+SecurityTestConfig), Cors, RateLimit, JWT, ExceptionHandler
│   ├── controller/       # REST Controllers (8)
│   ├── dto/              # Request/Response DTOs
│   ├── entity/           # JPA Entities (9)
│   ├── enums/            # Roles, OrderStatus, PaymentMethod, PaymentStatus
│   ├── repository/       # Spring Data JPA (7)
│   ├── service/          # Business Logic (6) + PaymentGateway + MockPaymentGateway
│   └── EshopApplication.java
├── src/main/resources/
│   ├── static/index.html # Frontend HTML/CSS/JS
│   └── application*.properties
├── src/test/java/com/eshop/
│   ├── EshopApplicationSmokeTest.java   # S0
│   ├── AbstractIntegrationTest.java     # base full-context
│   ├── config/          # S1 — JwtTokenProviderTest
│   ├── service/         # S2 — service tests (Mockito)
│   ├── controller/      # S3 — @WebMvcTest (+ControllerTestSupport)
│   └── integration/     # S4 — full-stack Testcontainers (+IntegrationTestSupport)
├── REBUILD_PLAN.md      # Piano test rebuild + bug B1–B8 + note tecniche
└── pom.xml
```
