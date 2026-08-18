# 🔨 E-Shop — Test Suite Rebuild Plan

> **Scopo**: rebuilding COMPLETO della test suite da zero, in piccoli passi (sezione per sezione).
> Ogni sezione va verificata con `mvn test` PRIMA di passare alla successiva.
> **Regola per agenti futuri**: leggi questo file all'inizio, trova la prima sezione non `DONE`,
> fallisci il passo, aggiorna lo status + il log qui sotto.

**Ultimo aggiornamento**: 2026-08-18 (sessione di rebuild #5 — S5 E2E Playwright DONE, suite completa)

---

## 1. Riepilogo del Progetto (punti chiave)

**Stack**: Java 21 · Spring Boot 3.4.1 · PostgreSQL 16 (Docker) · Maven · Lombok · Testcontainers 1.21.4 · Playwright 1.55 (E2E)

**Ambiente**
- Backend su porta **8081** (8080 occupato da llama-server)
- DB di sviluppo: container Docker `eshop-postgres` (user `eshop`, pass `eshop123`, db `eshop`) — al momento EXITED, `docker start eshop-postgres`
- Docker disponibile (verificato), Java 21.0.12, Maven 3.9.16

**Autenticazione & sicurezza**
- JWT: access 1h + refresh 24h, stateless, secret Base64 in `application.properties` (`app.jwt.*`)
- BCrypt per le password; ruoli `USER`/`ADMIN`
- Rate limiting (sliding window, in-memory, IP-based): login 30/min, register 10/min →
  **nei test il profilo `test` alza i limiti a 10000** (vedi §4)
- `SecurityConfig` è `@Profile("!test")`; nel profilo test si usa `SecurityTestConfig` (main source,
  `@Profile("test")`): `permitAll` su tutto + filter che autentica in base al **param query `?testUser=username`**
  (autorità ROLE_USER). Attenzione: NON esiste `spring.security.enabled=false` in Boot 3 — rimuovere da properties.

**Entità e relazioni**
```
User 1:1 Cart 1:N CartItem
User 1:N Order 1:N OrderItem
User 1:N PhoneNumber | User 1:N Address
Article 1:N CartItem, OrderItem | Category (name, parent gerarchia)
OrderPayment (order, paymentMethod, amount, status, transactionId, createdAt)
```
Nota: la classe si chiama **`Articles`** (entity) / `ArticlesRepository` / `ArticlesService` / `ArticlesController` (storia di rinomina).

**Business rules critiche (da testare)**
1. **Checkout 2 step**: `POST /api/orders/checkout/prepare` (riserva stock via `reservedStock`, crea `OrderPayment` PENDING, svuota carrello, expira in 30s) → `POST /api/orders/{id}/pay` (valida stato PENDING, scadenza, stock; successo → `PROCESSING`, stock dedotto, payment `CAPTURED`). Legacy `POST /api/orders/checkout` mantenuto.
2. **MockPaymentGateway**: 200ms delay, **1% random failure**, expiry 30s. (I test non devono dipendere dal caso: usare mock o ripetere con seed controllato dove possibile.)
3. **OrderStatus**: `PENDING → PROCESSING → SHIPPED → DELIVERED → COMPLETED`, `CANCELLED` solo da PENDING (ripristina stock). Transizioni validate in `isValidTransition()`.
4. **Prezzi**: `CartItem.unitPrice` si sincronizza SEMPRE con `articles.price` via `@PrePersist`/`@PreUpdate`; `OrderItem` blocca il prezzo al checkout (no listener).
5. **Profili**: `PUT /api/users/me/profile` richiede `currentPassword`.
6. **Admin-only**: POST/PUT/DELETE `/api/articles/**`, `PUT /api/orders/{id}/status`.
7. **BCrypt**: tutte le password hashate al registro; login funziona solo su hash.

**Endpoint principali** (full list nei controller `src/main/java/com/eshop/controller/`)
- Auth: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`
- Articles: `GET /api/articles` (pagination, search, category, price range, sort), `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`, `GET /by-author/{authorId}`
- Categories: `GET /api/categories`
- Cart: `POST /api/cart/items`, `GET /api/cart/me`, `GET /api/cart/total`, `DELETE /api/cart/items/{id}`, `DELETE /api/cart/clear`
- Orders: `GET /api/orders/my`, `GET /api/orders` (admin), `GET /api/orders/{id}`, `POST /api/orders/checkout/prepare`, `POST /api/orders/{id}/pay`, `POST /api/orders/{id}/cancel`, `PUT /api/orders/{id}/status`, `PUT /api/orders/{id}/complete`, legacy `POST /api/orders/checkout`
- User: `GET /api/users/me`, `PUT /api/users/me/profile`, `GET/POST/DELETE /api/users/me/phone...`, `GET/POST/DELETE /api/users/me/address...`

---

## 2. Inventario Test OLD (riferimento, rimossi il 2026-08-16)

32 file, ~330 metodi `@Test`. Rimossi completamente da `src/test/`.

| Pacch. | File (count metodi) | Note |
|---|---|---|
| `api/` | AuthApi(7) ArticlesApi(14) CartApi(10) OrderApi(15) OrderStatusApi(10) UserApi(11) | full-stack MockMvc |
| `controller/` | Auth(10) Articles(16) Cart(11) Order(15) User(6) Category(3) Address(7) PhoneNumber(9) | @WebMvcTest |
| `service/` | User(12) Articles(16) Cart(15) Order(12)+Prepare(10)+CompletePay(11)+CancelComplete(17) Phone(7) Address(7) MockPayment(12) | Mockito |
| `integration/` | Articles(11) Cart(10) Order(9) + IntegrationTestBase | Testcontainers |
| `config/` | JwtTokenProvider(23) | unit puro |
| `playwright/` | ShopFlow(12) Debug(2) + PlaywrightBase | E2E browser, serve app live |

---

## 3. Piano di Rebuild (sezioni)

| # | Sezione | Scope | Stato |
|---|---------|-------|-------|
| S0 | Infrastruttura test | `application-test.properties` (test profile), base class full-context, smoke test (context + DB round-trip) | ✅ DONE (12/12 → 2/2 test) |
| S1 | Unit — Config/JWT | `JwtTokenProvider`: create/parse/validate access+refresh, expired, tampered | ✅ DONE (12/12 test) |
| S2 | Unit — Services | UserService, ArticlesService, CartService, OrderService (checkout 2-step, pay, cancel, status), PhoneNumber, Address, MockPaymentGateway (Mockito) + `OrderStatus.isValidTransition` | ✅ DONE (113/113 test, 8 classi) |
| S3 | Unit — Controllers | @WebMvcTest per Auth, Articles, Category, Cart, Order, User, Address, PhoneNumber (mock services) | ✅ DONE (83/83 test, 8 classi) |
| S4 | Integration — full stack | @SpringBootTest + Testcontainers Postgres: Auth, Articles, Cart, Orders+Payment, User(profile/phone/address) | ✅ DONE (92/92 test, 6 classi) |
| S5 | E2E Playwright | ShopFlow (register→login→catalog→cart→checkout) + smoke; disabled di default (`-De2e.enabled=true`), run manuale con app live | ✅ DONE (12/12 test: smoke 3 + ShopFlow 9, vedi §3) |

**Convenzioni**
- Pacchetti mirror: `com.eshop.{config,service,controller,integration,playwright}`
- Nomi: `XxxServiceTest` (unit), `XxxControllerTest` (@WebMvcTest), `XxxIntegrationTest` / `XxxApiIntegrationTest` (full-stack)
- Full-context: estendere `AbstractIntegrationTest` (vedi S0)
- Ogni sezione finisce solo quando `mvn test` (o il subset di sezione) è verde

---

## 2.5 Bug scoperti (documentati nei test, NON modificato il codice app)

> B1–B5 scoperti in S2 (unit services); B6–B8 scoperti in S4 (integration full-stack, verificati su DB Testcontainers).

> Convenzione: i bug sotto sono documentati da test che asseriscono il **comportamento attuale**
> (con commento `⚠ KNOWN BUG` nel test). Se in futuro si corregge il codice, va aggiornato il test.

| # | Dove | Bug | Test che lo documenta |
|---|------|-----|------------------------|
| B1 | `OrderService.cancelOrder` / `completePayment` (fallimento gateway) | **Inflazione stock**: `prepareCheckout` solo *riserva* (non decrementa) lo stock, ma `cancelOrder` riaggiunge le quantità a stock già intatto. Dopo pagamento fallito o cancel di un ordine PENDING con qty 2: stock 10 → **12** (dovrebbe restare 10). | `OrderServiceTest#cancelPendingOrder`, `OrderServiceTest#completePaymentGatewayFailure` (asseriscono 12) |
| B2 | `OrderService.cancelOrder` | `cancelOrder` agisce SOLO su ordini PENDING: per PROCESSING/SHIPPED fa nulla, ma `isValidTransition` permette `PROCESSING→CANCELLED` / `SHIPPED→CANCELLED`. Incoerenza tra validazione transizioni e comportamento cancel. | `OrderServiceTest#cancelNonPendingOrder`, `OrderStatusTransitionTest` |
| B3 | `OrderStatus.isValidTransition` | Permette "salti" di stato (es. `PENDING→SHIPPED`, `PENDING→DELIVERED`, `PROCESSING→DELIVERED`) e **`DELIVERED→CANCELLED` è invalido** (il guard `from==DELIVERED && to!=COMPLETED` precede la regola generica `to==CANCELLED`). | `OrderStatusTransitionTest` (tabelle valid/invalid) |
| B4 | `CartService.addToCart` (path carrello assente) | La stessa istanza `Cart` viene salvata **2 volte** (una in `orElseGet`, una dopo `addItem`). Redundante ma innocuo in un solo `@Transactional`. | `CartServiceTest#addToCartCreatesCart` (`verify(..., times(2))`) |
| B5 | `AddressService.add` | `streetNumber = null` passa **indisturbato** nel service (nessun validation a livello service; `@Min(1)` esiste solo nel controller). | `AddressServiceTest#addNullStreetNumber` |
| B6 | `OrderPayment.@PrePersist` | `onCreate()` imposta **sempre** `status = PENDING` + `authorizedAt = now()`. Il `completePayment` fa `save()` DOPO aver impostato con il builder lo status restituito dal gateway (CAPTURED/AUTHORIZED) → `@PrePersist` lo sovrascrive: in DB e nella risposta `/pay` lo status è **sempre PENDING** (solo `transactionId`/`method`/`amount` sopravvivono). | `OrdersIntegrationTest#prepareThenPay_card_200_processing_stockDecremented`, `#pay_bankTransfer_*`, `#pay_cod_*` (asseriscono PENDING) |
| B7 | `GlobalExceptionHandler` | **`EntityNotFoundException` non mappata** → handler generico → **500** (non 404) su delete di phone/address di un altro utente (ownership check nel service). Nota: S3 già asseriva il 500 sulla slice; S4 lo conferma full-stack. | `UserIntegrationTest#phone_deleteOtherUserPhone_500_currentBehavior`, `#address_deleteOtherUserAddress_500_currentBehavior` |
| B8 | `OrderService.prepareCheckout` / `checkout` (legacy) | Il ramo `"Il carrello è vuoto"` è **irraggiungibile**: la query `findByUserIdWithItems` usa JOIN FETCH (inner join) su items → una cart esistente ma vuota non viene nemmeno trovata → 409 `"Carrello non trovato per utente: X"`. Code path morta in entrambi i metodi. | `OrdersIntegrationTest#prepare_emptyCart_409`, `#legacyCheckout_emptyCart_409` |

### Note tecniche (non bug, ma trappole per chi scrive test)

- **`User.builder()` lascia `phoneNumbers`/`addresses`/`orders` a `null`** (no `@Builder.Default` su quei
  campi, a differenza di `cart`/`role`). Nei test dei service che fanno `user.getAddresses().add(...)`
  usare `new User()` + setter. (Usato in `PhoneNumberServiceTest`, `AddressServiceTest`.)
- **`MockPaymentGateway` — 1% failure path NON pinnabile con `mockStatic(Math.class)`**: su JDK 21 +
  Mockito inline mock maker, instrumentare `java.lang.Math` (classe bootstrap) fa crashare il forked JVM
  con `StackOverflowError` (anche con `withSettings().stubOnly()`; provato e confermato).
  **Soluzione usata** in `MockPaymentGatewayTest`: test *invariante* — asserisce
  `success → status atteso + txn MOCK-*` OPPURE `failure → "Simulazione errore gateway" + txn null`,
  3 chiamate per metodo (probabilità di non beccare una regressione ≈ 10⁻⁶).
  Il path di fallimento a livello servizio è coperto deterministicamente in
  `OrderServiceTest#completePaymentGatewayFailure` (gateway mockato).
  Ogni chiamata al vero gateway costa ~200ms (Thread.sleep simulato).
- **DTO response `PhoneNumberResponse`/`AddressResponse` sono classi `@Data`** (getters `getId()`…),
  NON record. Gli `Add*Request` hanno costruttore all-args: `AddPhoneNumberRequest(countryPrefix, number, phoneType-String)`,
  `AddAddressRequest(street, streetNumber-Integer, postalCode, city, country)`.

---

## 2.6 Note tecniche S3 (trappole `@WebMvcTest`, verificate sul codice 2026-08-17)

- **Boot 3.4.1 NON ha `@MockitoBean`** (verificato ispezionando la jar): esistono solo
  `@MockBean` (deprecato, warning di compilazione accettabile) e `@SpyBean`. → usare **`@MockBean`**.
- **Dipendenza aggiunta al pom**: `spring-security-test` (test scope) — necessaria per `@WithMockUser`.
- **`@WebMvcTest` non carica `@Configuration` custom** (incl. `SecurityConfig`, che comunque è
  `@Profile("!test")`): in slice `@EnableMethodSecurity`/`@PreAuthorize` NON sono attivi → serve una
  test config condivisa `@Configuration @EnableMethodSecurity` da `@Import` in ogni controller test.
- **BLOCKER RISOLTO (2026-08-17)**: con il solo `@WebMvcTest(XxxController.class)` il contesto si carica
  ma contiene **zero bean dell'app** (niente controller → 404 su tutto; nel log Boot manca la riga
  "Found @SpringBootConfiguration" → `SpringBootTestContextBootstrapper.getOrFindConfigurationClasses`
  non raggiunge la ricerca e `EshopApplication`/`@ComponentScan` non vengono mai applicati; l'auto-config
  parte solo dalla catena `@ImportAutoConfiguration` della test class).
  **Soluzione**: aggiungere a ogni controller test
  **`@ContextConfiguration(classes = EshopApplication.class)`** → forza il component-scan filtrato da
  `WebMvcTypeExcludeFilter` (controller esplicito + `@RestControllerAdvice` + `Filter` + `WebMvcConfigurer` + auto-config).
- **Effetto collaterale della scan**: la slice RETIENE anche i bean `Filter`
  (`JwtAuthenticationFilter`, `RateLimitFilter`), i cui dipendenze (`JwtTokenProvider`,
  `RateLimitProperties`) non sono nella slice → **`@MockBean` sui due filter** (o sulle loro dipendenze)
  in ogni controller test.
- **`@AutoConfigureMockMvc(addFilters = false)`** per disabilitare la security filter chain di MockMvc.
- **`GlobalExceptionHandler`** (`@RestControllerAdvice`) viene caricato dalla slice → gli error mapping
  4xx/5xx sono testabili (es. `DataIntegrityViolationException`→500/409 a seconda dell'handler, 404 dal service).
- **Dipendenze da `@MockBean` per controller** (oltre ai due filter, vedi sopra):
  | Controller | Mock da aggiungere |
  |---|---|
  | Auth | `UserService`, `JwtTokenProvider` |
  | Articles | `ArticlesService`, `CurrentUser` |
  | Category | `ArticlesService` |
  | Cart | `CartService`, `CurrentUser` |
  | Order | `OrderService`, `CurrentUser` |
  | User | `UserService`, `CurrentUser` |
  | Address | `AddressService`, `CurrentUser` |
  | PhoneNumber | `PhoneNumberService`, `CurrentUser` |
  (`CurrentUser` è `@Component` → non in slice → va mockato ovunque serva.)
- **VERIFICATO (probe chiuso, 2026-08-17)**: con method security attiva (`@Import` di config
  `@EnableMethodSecurity`) e `@WithMockUser`, il denial di `@PreAuthorize("hasRole('ADMIN')")`
  produce **403** (AccessDeniedException mappata dalla servlet, body JSON `"Access denied"`).
  ⚠ **Quirk documentato**: senza autenticazione (nessun `@WithMockUser`) la stessa negazione
  finisce nell'handler generico → **500** (non 401/403). I test dei denial richiedono quindi
  `@WithMockUser` anche sui path di errore (404/500 di service) per endpoint admin.

### Risultato S3 — finding sul comportamento reale (utili per S4)

- **`POST /api/orders/checkout/prepare` NON ha `@PreAuthorize`** (nessuna protezione admin) →
  test documenta il 200 anche per un utente USER (`prepareCheckout_asNonAdmin_noRoleCheck_currentBehavior`).
- **`POST /api/orders/{id}/pay`** (è POST, non PUT) e il `@RequestBody PayOrderRequest` **non ha `@Valid`**:
  body `{}` → `method=null` arriva al service (test `pay_missingMethod_noValidAnnotation_currentBehavior`).
- **Path reali phone/address**: `/api/users/{userId}/phone/me[/{id}]` e `/api/users/{userId}/address/me[/{id}]`
  (la risorsa è sotto `/me`), e il `{userId}` nel path **non è mai letto**: l'utente viene da
  `?testUserId=` oppure `currentUser.getCurrentUserId()`.
- **`AdminOrderDto`**: campo id ordine è `id` (non `orderId`); `username` presente (Order.user è @JsonIgnore).
- **`GET /api/categories`** restituisce il `Set` così com'è (ordine non garantito) → nelle asserzioni
  usare `jsonPath("$.categories", containsInAnyOrder(...))` (hamcrest; `hasInAnyOrder` è AssertJ, non Hamcrest).
- **Service firmi verificate** (differenze rispetto ai nomi "attesi"): `AddressService.delete(userId, addressId)`
  e `PhoneNumberService.delete(userId, phoneId)` (2 args, userId primo); `OrderService.markAsCompleted(orderId, userId)`.
- **`@WithMockUser` serve su TUTTI i test di endpoint con `@PreAuthorize`** (anche 404/500 di service),
  altrimenti AccessDeniedException → 500 (quirk qui sopra).

## 2.7 Note tecniche S4 (trappole full-stack, verificate 2026-08-17)

**Infrastruttura** (pacchetto `com.eshop.integration`):
- `IntegrationTestSupport` (estesa da tutte le classi): `register`/`login` (reali, via API),
  `admin()` (utente ADMIN **seedato via JPA** — la API `register` hardcodifica `role=USER`, non esiste
  endpoint di creazione admin), `createArticle` (API, admin JWT), `setStock`/`stockOf`/`updatePrice` (JPA),
  `json`/`readJson` (Jackson `ObjectMapper` condiviso), nomi unici con suffisso `nanoTime` (il DB è
  **condiviso** tra tutte le classi nello stesso JVM: `create-drop` una volta all'avvio del contesto
  condiviso → nessun assertion di conteggio globale, sempre filtri per token/id).
- **2 contesti Spring**: uno condiviso (Auth/Articles/Cart/User/RealGateway) e uno separato per
  `OrdersIntegrationTest` (`@MockBean PaymentGatewayService` cambia la cache-key del contesto).
  `reset(paymentGateway)` in `@BeforeEach` per pulizia stub tra test.
- **`MockPaymentGateway`** nel contesto di default: 200ms di sleep + 1% failure casuale → i test di
  pagamento in `OrdersIntegrationTest` usano il gateway mockato (deterministico); solo
  `PaymentGatewayRealIntegrationTest` tocca il bean reale (verifica che sia `MockPaymentGateway`).

**Lazy loading (la trappola principale)** — in test thread, FUORI dalla sessione web:
- `Order.items` e `Order.payment` sono LAZY: MAI `o.getItems()`/`o.getPayment()` su un'entità carica
  con `findById` semplice. Usare `orderRepository.findByIdWithItems(id)` / `findByUserIdWithItems(id)`
  (JOIN FETCH) per gli items, e **`orderPaymentRepository.findByOrderId(id)`** (root entity → tutti i
  campi inizializzati) per i pagamenti. `o.getPayment()` su ordine senza pagamento restituisce null
  (niente proxy per FK null) ma non generalizzare: con payment → LazyInitializationException.
- `CartRepository.findByUserIdWithItems` NON trova cart vuote (inner join) → per asserire la cart
  vuota usare la API `GET /api/cart/me` e controllare `$.items.length()`. (Questo è anche la
  radice del bug B8.)

**Comportamenti API confermati full-stack** (coerenti con S3, qui verificati contro DB reale):
- `PUT /api/users/me/profile` legge solo le chiavi `email` e `password`: la chiave `username`
  viene **silenziosamente ignorata** (test dedicato).
- `GET /api/orders/{id}` è accessibile **anche anonimo** (nessun check ownership/ruolo nel controller):
  documentato come quirk (`findById_anonymousAccess_currentBehavior`).
- `pay` non aggiorna `Order.paymentMethod` (resta il metodo del prepare, default CREDIT_CARD);
  il metodo realmente usato vive solo su `OrderPayment`.
- `DELETE /api/cart/items/{id}` il path var è l'**articleId** (non l'itemId); rimuovere un articolo
  non presente è no-op 200. Svuota-carrello: `DELETE /api/cart/clear` (non `/api/cart`).
- Risposta validation: **mappa piatta** campo→messaggio (niente chiave `message`); `@Positive`/`@Min`
  lasciano passare il `null` → nel body `{}` compaiono solo i campi `@NotNull`/`@NotBlank`.
- `markAsCompleted` richiede stato DELIVERED (catena prepare→pay→admin status DELIVERED→complete).
- Admin orders: campo JSON `username` (non `user`; l'`@JsonPropertyOrder` elenca `user` ma è ignorato).
- `GET /api/users/me` su utente inesistente → `RuntimeException` → **500** (non 404).

**Auth/ruoli in test**:
- `SecurityTestConfig` (profilo test) è `permitAll` + `TestAuthFilter` (param `?testUser=` o
  `?testUserId=`): i request param vengono letti **dopo** il commit del request-scope transaction →
  l'utente deve esistere PRIMA della chiamata (mai `register` e subito `?testUser=` nella stessa istantanea
  senza flush; con il register via API è nativamente committed).
- `TestAuthFilter` crea un `ROLE_USER` (username come id): per i test ADMIN si usa il JWT reale
  dell'utente seedato via JPA (BCrypt + `role=ADMIN`). `SecurityTestConfig` NON ha
  `@EnableMethodSecurity` — eppure `@PreAuthorize` è attivo nel contesto integration: il
  component-scan del full-context porta dentro anche **`ControllerTestSupport.MethodSecurityConfig`**
  (classe nidificata `@Configuration @EnableMethodSecurity` di S3, in `com.eshop.controller`,
  senza `@Profile`) che registra gli advisor di method security. Verificato con probe sui bean
  (advisor `preAuthorizeAuthorizationAdvisor` presenti) + denial 403 nei test
  (`AuthIntegrationTest`). Attenzione: se si rimuove/riorganizza la suite S3, gli 403 admin in S4
  smetterebbero di funzionare (silently → 500/NPE): in quel caso portarsi `@EnableMethodSecurity`
  esplicitamente in `SecurityTestConfig`.

**Ordine di esecuzione e stato condiviso**: gli errori del primo run (14F+3E) venivano quasi tutti
o da asserzioni su campi JSON sbagliati (AdminOrderDto.username, formati dei messaggi "Stock
insufficiente per '<nome>"), path endpoint (cart clear/remove), o accessi lazy. Nessun bug di
infrastruttura: il setup di S0 (TC condiviso, `create-drop`, SecurityTestConfig) ha retto senza modifiche.

---

## 4. Note tecniche per il test setup

- **DB test**: Testcontainers via JDBC URL `jdbc:tc:postgresql:16:///eshop` (container auto-started per JVM, riusato tra test class nello stesso JVM). `ddl-auto=create-drop`. Serve Docker acceso.
- **Profilo test**: `@ActiveProfiles("test")` → attiva `SecurityTestConfig` (permitAll + `?testUser=xxx`) e disattiva `SecurityConfig` (@Profile("!test")).
- **Rate limit in test**: `app.rate-limit.*=10000`, `window-seconds=1` in `application-test.properties` (altrimenti 429 sui test auth).
- **JWT in test**: stesso secret da `application.properties` va bene; per unit test usare un secret proprio.
- **Auth nei test full-stack**: o login vero via API (JWT), o param `?testUser=username` (SecurityTestConfig) per cart/orders/user.
- **Playwright**: i test E2E NON devono girare in `mvn test` di default (servono app + browser). `@Disabled` di default.
- **Surefire**: già configurato in pom con `--add-opens java.base/java.lang=ALL-UNNAMED`.

---

## 5. Comandi

```bash
cd /home/carlo/eshop

# Tutto
mvn test

# Solo una sezione/classe
mvn test -Dtest=JwtTokenProviderTest
mvn test -Dtest="com.eshop.service.*"
mvn test -Dtest="com.eshop.integration.*"

# Solo main (no test)
mvn clean package -DskipTests

# DB di sviluppo
docker start eshop-postgres
docker exec -it eshop-postgres psql -U eshop -d eshop

# App
java -jar target/eshop-0.0.1-SNAPSHOT.jar   # http://localhost:8081
```

---

## 6. Log di Progresso

- **2026-08-16** — Sessione rebuild #1
  - Letti README, riepilogo, todolist, pom, config, properties, SecurityConfig/JWT/RateLimit.
  - Inventario test old: 32 file / ~330 metodi (§2). `src/test` rimosso integralmente.
  - **S0 DONE**: `application-test.properties` (TC JDBC URL `jdbc:tc:postgresql:16:///eshop`, `create-drop`,
    rate limit su `login-path.limit`/`register-path.limit` = 10000 ⚠️ campo giusto — i properties old
    usavano `app.rate-limit.login` che il filter NON legge; rimosso `spring.security.enabled=false` non
    valido in Boot 3) + `AbstractIntegrationTest` (@SpringBootTest + @ActiveProfiles("test"))
    + `EshopApplicationSmokeTest` (2 test).
  - **S1 DONE**: `JwtTokenProviderTest` (12 test, 4 nested class).
  - Verifica: `mvn test` → **BUILD SUCCESS, 14/14 test** (postgres:16 TC container start ~2s, riutilizzato nel JVM).
  - **NEXT (S2)**: unit services con Mockito. Ordine: 1) UserService, 2) CartService, 3) ArticlesService,
    4) OrderService (prepare/pay/cancel/status — attenzione al MockPaymentGateway: mockarlo, mai il vero
    per via del 1% random failure), 5) PhoneNumberService, 6) AddressService, 7) MockPaymentGatewayTest
    (unit del mock gateway stesso, senza delay: usare mockito o testare solo i path deterministici).

- **2026-08-16** — Sessione rebuild #2 — **S2 DONE**
  - Creati/completati 8 test class in `com.eshop.service` + `com.eshop.enums` (113 test totali S2):
    | Classe | Test |
    |---|---|
    | `UserServiceTest` | 20 (register/login/refresh/findByX/updateProfile) |
    | `OrderServiceTest` | 21 (prepare 2-step, pay, cancel, status, legacy checkout) |
    | `OrderStatusTransitionTest` | 27 (parametrizzati valid/invalid per `isValidTransition`) |
    | `CartServiceTest` | 14 (add/remove/clear/get/calculateTotal) |
    | `ArticlesServiceTest` | 11 (CRUD + search/filter/paginate) |
    | `PhoneNumberServiceTest` | 8 (find/add/delete + ownership) |
    | `AddressServiceTest` | 8 (find/add/delete + ownership) |
    | `MockPaymentGatewayTest` | 4 (invariant per metodo — vedi nota tecnica §2.5) |
  - **Correzioni ai test** (asserzioni sbagliate scritte in sessione #1, verificate sul codice reale):
    1. Stock assertion `10` → `12` in `cancelPendingOrder` e `completePaymentGatewayFailure`
       (bug B1: lo stock si *infla*, non si ripristina).
    2. `calculateTotal` `46.50` → `36.50` (errore aritmetico: 2×10.00 + 3×5.50).
    3. `addToCartCreatesCart`: `verify(save)` 1x → `times(2)` (bug B4: doppio save).
  - **`mockStatic(Math.class)` abbandonato**: fa crashare il forked JVM (StackOverflowError) su JDK 21
    anche in `stubOnly()` — Math è classe bootstrap. Sostituito con test invariante (§2.5).
  - Documentati bug B1–B5 in §2.5 (nessuna modifica al codice app).
  - Verifica: `mvn test` → **BUILD SUCCESS, 127/127 test** (S0 2 + S1 12 + S2 113).
  - **NEXT (S3)**: @WebMvcTest per i controller (Auth, Articles, Category, Cart, Order, User, Address,
    PhoneNumber) con service mockati. Attenzione ai DTO `@Data` vs record (vedi §2.5) e ai ruoli
    `@WithMockUser`/security test profile per gli endpoint admin-only.

- **2026-08-17** — Sessione rebuild #3 — **S3 IN PROGRESS** (indagini, suite non ancora scritta)
  - Mappati tutti i controller/DTO/entity e le firme dei service (tabella mock in §2.6).
  - Aggiunta dipendenza `spring-security-test` (test) al pom per `@WithMockUser`.
  - **Ritrovato/verificato**: Boot 3.4.1 non ha `@MockitoBean` → usare `@MockBean` (deprecato ma funzionante).
  - **Probe** `com.eshop.controller.ScratchPreAuthorizeProbeTest` (provvisorio, da cancellare a fine S3):
    - `@WebMvcTest(ArticlesController.class)` + `@MockBean ArticlesService` + `@MockBean CurrentUser`
      → contesto OK, ma **nessun bean dell'app presente** (solo auto-config + mock) → 404 su `/api/articles`.
    - Diagnosi: nel log manca "Found @SpringBootConfiguration" → l'auto-discovery non usa mai
      `EshopApplication` → nessun `@ComponentScan` → nessun controller. (Verificati pom, app class,
      `PathConfig`, properties: nessun colpevole evidente; comportamento anomalo rispetto all'upstream.)
    - **Fix valido**: `@ContextConfiguration(classes = EshopApplication.class)` sulla test class → il
      component-scan in slice parte davvero; nuovo errore atteso e previsto: bean `Filter` ritenuti dalla
      slice chiedono dipendenze assenti (`JwtTokenProvider`) → da mockare (`@MockBean JwtAuthenticationFilter`
      e `@MockBean RateLimitFilter`).
  - **TODO immediato (prossima sessione)**:
    1. Chiuso il probe: mock filter + verifica status del denial `@PreAuthorize` (403 vs altro) con
       `@Import` di una config `@EnableMethodSecurity`.
    2. Config di method-security condivisa in `com.eshop.controller` (o `test`) + factory entity/DTO condivisa.
    3. Scrivere le 8 `XxxControllerTest` (~77 test: Auth 10, Articles 16, Category 3, Cart 11, Order 15,
       User 6, Address 7, PhoneNumber 9) — success, 400 validation, 404, 409, 403 admin con
       `@WithMockUser(roles=...)`.
    4. `mvn test -Dtest="com.eshop.controller.*"` poi `mvn test` completo (attesi ≈204 test).
    5. Cancellare `ScratchPreAuthorizeProbeTest` e il `src/test/resources/logback-test.xml` provvisorio
       usato per le diagnosi.
    6. Aggiornare questo piano (S3 → DONE + numeri).

- **2026-08-17** — Sessione rebuild #3 (continuazione) — **S3 DONE**
  - Probe chiuso: con `@Import` di `ControllerTestSupport.MethodSecurityConfig` (`@Configuration @EnableMethodSecurity`)
    il denial `@PreAuthorize` → **403**; senza auth → **500** via handler generico (quirk documentato, §2.6).
  - Creata `com.eshop.controller.ControllerTestSupport`: config method-security condivisa +
    `TestFixtures` (factory User/Articles/CartItem/Cart/Order/OrderItem/Page/risposte DTO).
  - Scritte le 8 classi (83 test): Auth 10 · Articles 16 · Category 3 · Cart 11 · Order 21 ·
    User 6 · Address 7 · PhoneNumber 9. Copertura: 2xx success, 400 validation (@Valid),
    400/404/409/500 da eccezioni service (mapping GlobalExceptionHandler), 403 admin
    (`@WithMockUser(roles=...)`), ownership 403 (testUserId), path 500 documentati
    (EntityNotFoundException→500 su address/phone delete; RuntimeException→500 su
    `GET /me` user non trovato e `refresh` con user orphano).
  - **Correzioni rispetto alle ipotesi iniziali** (verificate sul codice, dettagli in §2.6):
    pay è `POST /{id}/pay` (non PUT) e senza `@Valid`; `/checkout/prepare` senza `@PreAuthorize`;
    path phone/address sotto `/me`; `AdminOrderDto.id` (non orderId); `delete(userId, id)` 2 args;
    `markAsCompleted(orderId, userId)`; `containsInAnyOrder` (hamcrest) per l'ordine del Set categorie.
  - Rimossi `ScratchPreAuthorizeProbeTest` e `src/test/resources/logback-test.xml` (provvisori).
  - Verifica: `mvn test` → **BUILD SUCCESS, 210/210 test** (S0 2 + S1 12 + S2 113 + S3 83).
  - **NEXT (S4)**: integration full-stack @SpringBootTest + Testcontainers (estendere
    `AbstractIntegrationTest`): Auth, Articles, Cart, Orders+Payment, User(profile/phone/address).
    Attenzione: rate limit già alzati in `application-test.properties`; MockPaymentGateway ha
    1% failure casuale + 200ms delay (vedi §2.5) — i test di pagamento vanno pensati come
    invariant o con gateway mockato/patched.

- **2026-08-17** — Sessione rebuild #4 — **S4 DONE**
  - Scritte 6 classi in `com.eshop.integration` (**92 test**):
    | Classe | Test | Copertura |
    |---|---|---|
    | `AuthIntegrationTest` | 19 | register (201/400/409 dup), login (200/400 cred errate/404), admin login, refresh (200/401/500), e2e Bearer su `/users/me`, testUser param, denial admin 403 |
    | `ArticlesIntegrationTest` | 14 | create 201/400, list/paginate, filtri price/category/author, findById 404, byAuthor, update 200/404, delete 200/404 |
    | `CartIntegrationTest` | 12 | cart vuoto, add/total, reAdd, price-sync (⚠ unitPrice stale finché non `@PreUpdate`), remove (articleId), clear, 409 stock, 404 articolo, 400 validation, isolamento utenti |
    | `OrdersIntegrationTest` | 28 | prepare 200/409, pay (CAPTURED/AUTHORIZED→**B6 PENDING**), gateway failure 500+rollback, pay senza method 500, myOrders (Page, filtro status), admin orders (username+items), status update valido/invalido, complete (DELIVERED→COMPLETED), cancel (B1/B2), legacy checkout 200/409, ownership 404 |
    | `UserIntegrationTest` | 18 | /me (200/Bearer/500 ignoto), profile (email/password/username-ignorato), phone add/list/delete/400/**500 ownership (B7)**, address analogo (streetNumber-null passa) |
    | `PaymentGatewayRealIntegrationTest` | 1 | bean reale del contesto = `MockPaymentGateway` |
    + `IntegrationTestSupport` (helper condivisi: register/login via API, admin via JPA, articoli, stock).
  - **Run 1**: 92 test → 14F+3E. Radici (tutte da test-side, zero modifiche all'app):
    1. `paymentStatus` sempre **PENDING** → bug **B6** (`OrderPayment.@PrePersist`); asserzioni corrette
       + test rinominati `*_bugB6` (transactionId/method continuano a provare il flusso gateway).
    2. `AdminOrderDto`: campo JSON è `username` (non `user`).
    3. Accessi lazy in test thread → `findByIdWithItems` / `orderPaymentRepository.findByOrderId`
       (mai `o.getItems()`/`o.getPayment()` su entità `findById` semplice).
    4. Cart vuota: `findByUserIdWithItems` (JOIN FETCH) non la trova → asserzioni via API `GET /api/cart/me`
       e messaggi 409 `"Carrello non trovato per utente: X"` (ramo `"Il carrello è vuoto"` irraggiungibile → **B8**).
    5. Path endpoint cart: `DELETE /api/cart/items/{articleId}` e `DELETE /api/cart/clear`.
    6. Validation: mappa piatta, `@Positive`/`@Min` lasciano passare null → solo chiavi `@NotNull`/`@NotBlank`.
    7. Phone/address delete di un altro utente → **500** (B7, coerente con S3) non 404.
    8. `pay` non aggiorna `Order.paymentMethod` (resta CREDIT_CARD del prepare; il metodo è su `OrderPayment`).
  - Documentati bug **B6–B8** in §2.5 + note tecniche S4 in §2.7. Rimosso `ScratchProbeTest` (provvisorio).
  - Verifica: `mvn test` → **BUILD SUCCESS, 302/302 test** (S0 2 + S1 12 + S2 113 + S3 83 + S4 92),
    ~2-3 min con container TC Postgres 16 riusato.

- **2026-08-18** — Sessione rebuild #5 — **S5 DONE — rebuild della suite COMPLETO (S0–S5)**
  - Scritte 3 classi in `com.eshop.playwright` (**12 test**):
    | Classe | Test | Copertura |
    |---|---|---|
    | `PlaywrightBase` | — | Base gated (`@EnabledIfSystemProperty e2e.enabled` su classe base → ereditato, niente `@Disabled` letterale): liveness check app (se down → "run ./start.sh"), browser Chromium headless per classe, `BrowserContext` fresh per test (localStorage pulito, logged-out), helper API (login/register/articoli ADMIN/`/cart/clear`) + helper UI (login/register form, add-to-cart con sync via `GET /api/cart/me`, `completeCheckout` 3 tentativi retry-tolerant sul 1% failure del `MockPaymentGateway`, `payInModal` che discrimina success badge / toast errore) |
    | `PlaywrightSmokeTest` | 3 | app boot + auth screen, login admin → UI admin (`#adminBadge`, `#adminTab`), register utente → NO UI admin + `#sellBtn` visibile |
    | `ShopFlowTest` | 9 | register→catalog, login pw errata → toast "Credenziali non valide" e resto su auth, catalogo (prezzo € + stock text `3 disponibili`/`Disponibile`, search live da `#searchInput` con token unico per run), carrello (badge = item TYPE non quantità, modal nome/`€X cad.`/qty/totale, doppio add → qty 2), **ShopFlow completo** (register→catalogo→cart 2 articoli→checkout 2-step→pay carta→ordine PROCESSING in `#ordersList` con `Totale: €21.00`→stock decrementato in catalogo, formula che tiene conto di eventuali failure 1%: `stock = init - qty + f·qty`, bug B1), checkout COD (`Metodo: Contrassegno`, badge→0, ordine PROCESSING), ordine pagato visibile nel tab admin (id/status/riga articolo/`Totale` — username NON asserito, vedi note) |
  - **Esecuzione** (manuale, app live obbligatoria):
    ```
    ./start.sh
    mvn test -Dtest="PlaywrightSmokeTest,ShopFlowTest" -De2e.enabled=true
    ```
    → **12/12 GREEN** (~90s). Run di default (`mvn test`) non cambia: S5 gated off, 302/302 verdi.
  - **Learnings (Playwright Java 1.55 — API diversa da Node)**:
    1. `assertThat(locator)` (non `expect`); metodi: `isVisible()`, `isHidden()`, `hasCount(int)`,
       `hasText(String|Pattern|String[])` = **exact match** (whitespace normalizzato),
       `containsText(String|Pattern)` = substring/regex. Niente `toBeVisible`/`toHaveText`/`toHaveCount`.
    2. `Page.LocatorOptions().setHasText(String)` per `page.locator(sel, opts)`; `waitForSelector(sel,` →
       `Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED|VISIBLE|HIDDEN).setTimeout(ms)`;
       timeout globale asserzioni: `PlaywrightAssertions.setDefaultAssertionTimeout(ms)`.
    3. `@TestInstance(PER_CLASS)` ereditato da `PlaywrightBase` → `@BeforeAll`/`@AfterEach` d'istanza;
       browser lanciato una volta per classe (i test della stessa classe condividono il browser ma NON
       il context: `BrowserContext` fresh per test = stato pulito senza ri-navigation costoso).
    4. JUnit 5.11: `@EnabledIfSystemProperty(named=..., matches=...)` (attributo `named`, non `name`);
       l'annotazione va sulla classe base (è heritable) così la run manuale non richiede modifiche al codice.
    5. I radio del payment sono `display:none` (custom-styled) → `page.check()` fallisce sulla visibilità:
       click sul `label[for='pm_cod']` invece; readiness del payment modal = `#payBtn` visibile.
  - **Comportamento attuale documentato nei test (UI/DTO mismatch, non asserzioni "ideali")**:
    - `#sellBtn` visibile solo per **USER** (l'ADMIN vede `#statsMenuItem`), non USER+ADMIN come da note S4.
    - Admin orders: `AdminOrderDto` espone `username` a livello flat (template legge `order.user?.username`)
      → UI mostra sempre `Utente: N/A`; item name: DTO flat `articleName` (template legge
      `item.articles?.name`) → UI mostra sempre `• Articolo xN @ €X.XX`. Entrambi NON asseriti/
      asseriti come fallback (il buyer `/orders/my` RESTITUISCE articoli annidati → i nomi
      invece ci sono e vengono asseriti).
    - `#cartBadge` = numero di item TYPE (`cart.items.length`), non somma quantità (UI linea 4291).
    - Toast errore gateway: `showToast(error.message || 'Errore nel pagamento')` (non il literal
      `Gateway payment error` del piano §2.5) + chiusura modal + `currentPaymentData=null`
      → il retry del checkout riparte da carrello vuoto (comportamento coerente col rollback).
  - **Robustezza**: utenti/articoli con suffisso `RUN_ID` (nanoTime) → immune al DB dev sporco;
    search catalog col token privato invece di assumere catalogo vuoto; 1 solo buyer condiviso
    creato via API in `@BeforeAll` (register via UI solo dove testato → pressione rate-limit minima);
    ordine individuato SEMPRE per `Ordine #<id>` (mai `.first()`) perché JUnit esegue i metodi
    in ordine obfuscated e tutti i test creano ordini pagati.
  - Verifica finale: `mvn test` (default) → **302/302 verdi** + S5 12/12 verdi (run manuale).
  - **REBUILD S0–S5: COMPLETATO.** Nessun next step pianificato; eventuali feature future in todolist.txt.
