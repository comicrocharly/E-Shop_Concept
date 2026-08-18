package com.eshop;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for all full-context tests (integration / API integration).
 *
 * <p>Boots the complete Spring application with the {@code test} profile, which:
 * <ul>
 *   <li>activates {@code SecurityTestConfig} (permitAll + {@code ?testUser=xxx} auth)
 *       and disables {@code SecurityConfig} ({@code @Profile("!test")});</li>
 *   <li>points the datasource at a Testcontainers PostgreSQL 16 container
 *       (auto-started via the {@code jdbc:tc:} URL in {@code application-test.properties});</li>
 *   <li>raises rate limits so auth endpoints never return 429 in tests.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
}
