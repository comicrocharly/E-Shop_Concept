package com.eshop.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Browser.NewContextOptions;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserType.LaunchOptions;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Page.LocatorOptions;
import com.microsoft.playwright.Page.WaitForSelectorOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base for the S5 E2E tests (Playwright 1.55, Chromium).
 *
 * <p><b>Disabled by design</b> (REBUILD_PLAN.md §2.5 / S5): these tests run against the
 * <i>live</i> application (dev profile, port 8081) and the dev DB, so they are excluded
 * from the default {@code mvn test}. The gate is {@code @EnabledIfSystemProperty} on this
 * base class (inherited by the test classes) — deliberately not a literal {@code @Disabled},
 * so a manual run needs no code change:
 *
 * <pre>{@code
 *   ./start.sh
 *   mvn test -Dtest="PlaywrightSmokeTest,ShopFlowTest" -De2e.enabled=true
 * }</pre>
 *
 * <p>Configurable via system properties:
 * <ul>
 *   <li>{@code e2e.enabled} — required to run at all</li>
 *   <li>{@code e2e.baseUrl} — default {@code http://localhost:8081}</li>
 *   <li>{@code e2e.headed} — default {@code false}</li>
 *   <li>{@code e2e.adminUsername} / {@code e2e.adminPassword} — admin seed user from
 *       CREDENTIALS.md (article creation + admin-tab checks)</li>
 * </ul>
 *
 * <p>Conventions: every test gets a <b>fresh BrowserContext</b> (clean localStorage,
 * logged-out state) pointed at the app home. Test users and articles are uniquely suffixed
 * per run ({@code e2e-*&lt;runId&gt;*}); the dev DB is <b>not</b> wiped between runs, so a
 * test that needs an isolated catalog searches by its private token instead of assuming
 * an empty database. The mock payment gateway has a 1% random failure
 * ({@code MockPaymentGateway}) — checkout flows retry and tolerate it (see
 * {@link #completeCheckout}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "e2e.enabled", matches = "true")
abstract class PlaywrightBase {

    protected static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8081");
    protected static final String ADMIN_USERNAME = System.getProperty("e2e.adminUsername", "adminuser");
    protected static final String ADMIN_PASSWORD = System.getProperty("e2e.adminPassword", "admin123");
    protected static final boolean HEADED = Boolean.parseBoolean(System.getProperty("e2e.headed", "false"));

    /** Unique per JVM run — suffix for test users / articles / search tokens. */
    protected static final long RUN_ID = System.nanoTime() % 1_000_000_000L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final int TIMEOUT_MS = 15_000;

    protected static Playwright playwright;
    protected static Browser browser;

    /** Access token of the admin user, resolved once per test class. */
    protected String adminToken;
    protected BrowserContext context;
    protected Page page;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @BeforeAll
    void startE2EEnvironment() {
        // 1) The app must be up (manual run against the live instance, REBUILD_PLAN.md S5).
        try {
            HttpResponse<String> health = HTTP.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/api/categories"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(),
                    "app not reachable at " + BASE_URL + " (status " + health.statusCode()
                            + ") — start it first: ./start.sh");
        } catch (Exception e) {
            fail("app not reachable at " + BASE_URL + " — start it first: ./start.sh (" + e.getMessage() + ")");
        }

        // 2) The admin user must exist (needed to seed articles via the admin API).
        adminToken = apiLogin(ADMIN_USERNAME, ADMIN_PASSWORD);

        // 3) Browser (Chromium, headless by default).
        PlaywrightAssertions.setDefaultAssertionTimeout(TIMEOUT_MS);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new LaunchOptions()
                .setHeadless(!HEADED)
                .setArgs(List.of("--disable-dev-shm-usage")));
    }

    @AfterAll
    void stopE2EEnvironment() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void freshBrowserContext() {
        context = browser.newContext(new NewContextOptions()
                .setViewportSize(1280, 900)
                .setLocale("it-IT"));
        context.setDefaultTimeout(TIMEOUT_MS);
        page = context.newPage();
        page.navigate(BASE_URL);
        // Known start state: logged out, auth screen visible (uses the UI's own test helper).
        page.evaluate("() => window.__eshopTest.clearAuth()");
        PlaywrightAssertions.assertThat(page.locator("#authSection")).isVisible();
    }

    @AfterEach
    void closeBrowserContext() {
        if (context != null) context.close();
    }

    // ------------------------------------------------------------------
    // API helpers (setup only: user creation, article seeding, cart reset)
    // ------------------------------------------------------------------

    /** Minimal HTTP client for API-side setup. Does NOT throw on 4xx/5xx. */
    protected HttpResponse<String> http(String method, String path, Object jsonBody, String bearerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(15));
        if (bearerToken != null) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        if (jsonBody != null) {
            String payload = jsonBody instanceof String s ? s : JSON.writeValueAsString(jsonBody);
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(payload));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected String apiLogin(String username, String password) {
        try {
            HttpResponse<String> r = http("POST", "/api/auth/login",
                    Map.of("username", username, "password", password), null);
            assertEquals(200, r.statusCode(), "login as " + username + " failed: " + r.body());
            return JSON.readTree(r.body()).get("accessToken").asText();
        } catch (Exception e) {
            throw asRuntime(e);
        }
    }

    /** Registers a user via the API (register + login, same as the UI flow) and returns the access token. */
    protected String registerAndLoginViaApi(String username, String email, String password) {
        try {
            HttpResponse<String> r = http("POST", "/api/auth/register",
                    Map.of("username", username, "email", email, "password", password), null);
            assertTrue(r.statusCode() == 200 || r.statusCode() == 201,
                    "register " + username + " failed: " + r.body());
            return apiLogin(username, password);
        } catch (Exception e) {
            throw asRuntime(e);
        }
    }

    /**
     * Creates an article via the ADMIN API (the public API has no article write endpoint)
     * and returns the new article id.
     */
    protected long createArticleViaApi(String name, String description, double price, int stock) {
        try {
            HttpResponse<String> r = http("POST", "/api/articles",
                    Map.of("name", name, "description", description, "price", price, "stock", stock),
                    adminToken);
            assertEquals(201, r.statusCode(), "article creation failed: " + r.body());
            return JSON.readTree(r.body()).get("id").asLong();
        } catch (Exception e) {
            throw asRuntime(e);
        }
    }

    /** Best-effort cart reset for the given token (a missing cart is fine). */
    protected void clearCartViaApi(String token) {
        try {
            http("DELETE", "/api/cart/clear", null, token);
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    protected Locator productCard(long articleId) {
        return page.locator(".product-card[data-product-id='" + articleId + "']");
    }

    /** Types in the catalog search box (triggers the UI's live {@code filterProducts()}). */
    protected void searchCatalog(String query) {
        page.fill("#searchInput", query);
    }

    /** Logs in through the auth form (initial form mode is LOGIN). */
    protected void loginViaUi(String username, String password) {
        PlaywrightAssertions.assertThat(page.locator("#authSection")).isVisible();
        PlaywrightAssertions.assertThat(page.locator("#authTitle")).hasText("Accedi");
        page.fill("#username", username);
        page.fill("#password", password);
        page.click("#authSubmit");
        PlaywrightAssertions.assertThat(page.locator("#appSection")).isVisible();
        PlaywrightAssertions.assertThat(page.locator("#authSection")).isHidden();
    }

    /** Registers through the auth form (toggles to "Registrati") and lands in the app. */
    protected void registerViaUi(String username, String email, String password) {
        PlaywrightAssertions.assertThat(page.locator("#authSection")).isVisible();
        page.click("#authToggleLink");
        PlaywrightAssertions.assertThat(page.locator("#authTitle")).hasText("Registrati");
        PlaywrightAssertions.assertThat(page.locator("#authEmailField")).isVisible();
        page.fill("#username", username);
        page.fill("#email", email);
        page.fill("#password", password);
        page.click("#authSubmit");
        PlaywrightAssertions.assertThat(page.locator("#appSection")).isVisible();
        PlaywrightAssertions.assertThat(page.locator("#authSection")).isHidden();
    }

    /**
     * Clicks the card's "Aggiungi al carrello" button {@code qty} times, waiting after each
     * click until the API-side cart reaches the expected quantity (the UI updates async).
     *
     * <p>NOTE: the badge (#cartBadge) shows the number of item <i>types</i>
     * ({@code cart.items.length}), not the sum of quantities.
     */
    protected void addToCartViaUi(long articleId, int qty, String buyerToken) {
        for (int target = 1; target <= qty; target++) {
            productCard(articleId).locator(".add-to-cart-btn").click();
            waitForCartQuantityViaApi(buyerToken, articleId, target);
        }
    }

    /**
     * Full cart → checkout → pay flow (UI), with retry against the mock gateway's 1% random
     * failure ({@code MockPaymentGateway}: "Simulazione errore gateway", order rolled back
     * to CANCELLED and the cart cleared — the retry restarts from an empty cart).
     *
     * <p>Sequence per attempt: add items (UI) → open cart modal → "Procedi al Checkout"
     * (cart screen) → "Procedi al Checkout" (payment modal) → pay.
     *
     * @param buyerToken           token of the user whose cart is filled (API wait sync)
     * @param cartPlan             alternating {@code articleId, qty, articleId, qty, ...}
     * @param paymentMethodRadioId {@code "#pm_credit"}, {@code "#pm_cod"} or null (default)
     * @return the payment-modal success body, gateway-failure count and checkout totals
     */
    protected CheckoutResult completeCheckout(String buyerToken, long[] cartPlan, String paymentMethodRadioId) {
        int failures = 0;
        String cartModalTotal = null;
        String cartScreenSummary = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            for (int i = 0; i < cartPlan.length; i += 2) {
                addToCartViaUi(cartPlan[i], (int) cartPlan[i + 1], buyerToken);
            }
            page.click("#cartToggleBtn");
            PlaywrightAssertions.assertThat(page.locator("#cartModal.open")).isVisible();
            PlaywrightAssertions.assertThat(page.locator("#cartModalFooter")).isVisible();
            cartModalTotal = page.locator("#cartModalTotal").innerText();
            page.locator("#cartModalFooter button", new LocatorOptions().setHasText("Procedi al Checkout")).click();
            PlaywrightAssertions.assertThat(page.locator("#cartScreen.active")).isVisible();
            cartScreenSummary = page.locator("#cartScreenSummary").innerText();
            page.locator("#cartScreenSummary button", new LocatorOptions().setHasText("Procedi al Checkout")).click();
            PlaywrightAssertions.assertThat(page.locator("#payBtn")).isVisible(); // payment modal rendered

            if (payInModal(paymentMethodRadioId)) {
                String successBody = page.locator("#paymentModalBody").innerText();
                return new CheckoutResult(successBody, failures, cartModalTotal, cartScreenSummary,
                        orderIdFromSuccessBody(successBody));
            }
            failures++;
            // 1% gateway failure: order CANCELLED (rolled back), cart cleared → retry fresh
            page.locator("#paymentModalBody button", new LocatorOptions().setHasText("Annulla")).click();
        }
        fail("checkout failed 3 times in a row — gateway 1% failure ×3 (probability ~1e-6)");
        return null; // unreachable
    }

    /**
     * Clicks "Paga" in the open payment modal.
     *
     * @return true on the success screen (green badge), false on the 1% gateway failure
     *         (error toast + "Riprova" button)
     */
    protected boolean payInModal(String paymentMethodRadioId) {
        // Drain stale error toasts from a previous attempt (they live ~3s) to avoid false negatives.
        waitForNoErrorToast();
        if (paymentMethodRadioId != null) {
            // The radio inputs are display:none (custom-styled) → click the visible label.
            String radioId = paymentMethodRadioId.startsWith("#")
                    ? paymentMethodRadioId.substring(1) : paymentMethodRadioId;
            page.click("label[for='" + radioId + "']");
        }
        page.click("#payBtn");
        ElementHandle done = page.waitForSelector(".payment-status-badge.success, .toast.error",
                new WaitForSelectorOptions().setTimeout(30_000));
        if (done == null) {
            fail("no payment outcome within 30s");
        }
        String classes = (String) done.evaluate("el => el.className");
        boolean ok = classes != null && classes.contains("payment-status-badge");
        if (!ok) {
            waitForNoErrorToast();
        }
        return ok;
    }

    /** Switches to the buyer "Ordini" tab (order list loads async). */
    protected void openMyOrdersTab() {
        page.locator(".nav-tab", new LocatorOptions().setHasText("Ordini")).click();
        PlaywrightAssertions.assertThat(page.locator("#ordersList")).isVisible();
    }

    private void waitForNoErrorToast() {
        try {
            page.waitForSelector(".toast.error",
                    new WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED).setTimeout(5_000));
        } catch (RuntimeException ignored) {
            // timeout: cannot happen, toasts are removed after 3s
        }
    }

    private void waitForCartQuantityViaApi(String token, long articleId, int expectedQuantity) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            try {
                HttpResponse<String> r = http("GET", "/api/cart/me", null, token);
                if (r.statusCode() == 200) {
                    for (JsonNode item : JSON.readTree(r.body()).path("items")) {
                        if (item.path("articles").path("id").asLong() == articleId
                                && item.path("quantity").asInt() >= expectedQuantity) {
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {
                // transient error: retry
            }
            if (System.currentTimeMillis() > deadline) {
                fail("cart of article " + articleId + " did not reach quantity "
                        + expectedQuantity + " within 10s");
            }
            sleep(150);
        }
    }

    protected int orderIdFromSuccessBody(String successBody) {
        Matcher m = Pattern.compile("Il tuo ordine #(\\d+)").matcher(successBody);
        assertTrue(m.find(), "order id not found in payment success screen: " + successBody);
        return Integer.parseInt(m.group(1));
    }

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    private static RuntimeException asRuntime(Exception e) {
        return e instanceof RuntimeException r ? r : new RuntimeException(e);
    }

    /**
     * Result of {@link #completeCheckout}: the payment-modal success-screen text, the number
     * of gateway 1% failures hit (each one inflates stock by the ordered quantities — bug B1,
     * order rollback re-adds stock that was never decremented), and the captured checkout totals.
     */
    protected record CheckoutResult(String successBody, int gatewayFailures, String cartModalTotal,
                                    String cartScreenSummary, int orderId) {
    }
}
