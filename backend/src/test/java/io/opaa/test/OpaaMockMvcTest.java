package io.opaa.test;

import io.opaa.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Canonical context signature for controller-level tests that drive the app through MockMvc:
 * {@code @SpringBootTest} (default MOCK web environment), {@code @AutoConfigureMockMvc}, the shared
 * {@link TestcontainersConfiguration} container, and {@code @ActiveProfiles("dev")}.
 *
 * <p>Established by #648 for the controller-authorization tests before this issue (#843) named it:
 * every class carrying this exact signature shares one Spring context and one Testcontainers
 * Postgres instance. A class that additionally declares its own {@code @DynamicPropertySource} or
 * {@code @MockitoBean} set still gets its own context regardless (Spring's cache key includes
 * both), so such classes stay on this meta-annotation but keep a short comment explaining why they
 * cannot share.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
public @interface OpaaMockMvcTest {}
