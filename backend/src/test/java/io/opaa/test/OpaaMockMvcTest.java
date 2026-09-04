package io.opaa.test;

import io.opaa.TestcontainersConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
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
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
public @interface OpaaMockMvcTest {

  /**
   * Constant `key=value` overrides for this context only - a class-specific escape hatch for the
   * {@code properties} attribute of the meta-annotated {@link SpringBootTest}. Values read at
   * runtime from a resource (e.g. a Testcontainers container) still belong in the test class's own
   * {@code @DynamicPropertySource}, not here.
   *
   * <p>A class overriding {@code properties()} replaces the whole array (an {@code @AliasFor} array
   * attribute does not merge - see {@link OpaaIndexingIntegrationTest}'s own Javadoc for the same
   * caveat) and thereby leaves the shared context this signature otherwise provides.
   */
  @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
  String[] properties() default {};
}
