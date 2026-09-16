package com.eshop.playwright;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * S5 — smoke tests: the app boots and the auth/login UI works end to end.
 *
 * <p>Disabled by default; see {@link PlaywrightBase} for how to run the S5 suite manually.
 */
@EnabledIfSystemProperty(named = "e2e.enabled", matches = "true")
class PlaywrightSmokeTest extends PlaywrightBase {

    @Test
    void appBootsAuthScreenVisible() {
        assertThat(page.locator("#authSection")).isVisible();
        assertThat(page.locator("#authTitle")).hasText("Accedi");
        assertThat(page.locator("#authForm")).isVisible();
        assertThat(page.locator("#appSection")).isHidden();
    }

    @Test
    void adminLoginViaUiShowsAdminUi() {
        loginViaUi(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(page.locator("#appSection")).isVisible();
        assertThat(page.locator("#authSection")).isHidden();
        assertThat(page.locator("#currentUsername")).hasText(ADMIN_USERNAME);
        assertThat(page.locator("#adminBadge")).isVisible();      // "ADMIN" badge
        assertThat(page.locator("#adminTab")).isVisible();        // Admin nav tab (ADMIN only)
        assertThat(page.locator("#sellBtn")).isHidden();          // sellBtn is USER-only (current behavior)
    }

    @Test
    void registerViaUiRegularUserGetsNoAdminUi() {
        String user = "e2e-smoke-" + RUN_ID;
        registerViaUi(user, user + "@e2e.local", "E2ePass!123");
        assertThat(page.locator("#appSection")).isVisible();
        assertThat(page.locator("#authSection")).isHidden();
        assertThat(page.locator("#currentUsername")).hasText(user);
        assertThat(page.locator("#adminBadge")).isHidden();
        assertThat(page.locator("#adminTab")).isHidden();   // in DOM but hidden for non-admins
        assertThat(page.locator("#sellBtn")).isVisible(); // Vendi: USER-only header button
    }
}
