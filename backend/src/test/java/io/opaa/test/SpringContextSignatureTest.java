package io.opaa.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Draws the line AGENTS.md, "Spring-Testkontexte", describes: the whole backend suite runs on the
 * signatures listed in {@link #CANONICAL_SIGNATURES} and starts exactly that many Spring contexts -
 * and therefore that many Testcontainers Postgres instances, since {@code
 * TestcontainersConfiguration} declares the container as an ordinary singleton bean.
 *
 * <p>{@code MergedContextConfiguration} <em>is</em> Spring's context cache key, and {@link
 * BootstrapUtils} builds it per test class without starting anything - so this check is a classpath
 * scan, not a test run. A class that adds any differentiator of its own (an {@code @Import}, a
 * {@code @DynamicPropertySource}, a {@code @MockitoBean} field, a nested
 * {@code @TestConfiguration}) lands in a group of its own and fails here, named.
 *
 * <p><b>Three known gaps, all accepted:</b> the scan sees no {@code @Nested} inner class (they
 * inherit their enclosing class's configuration anyway), it filters on {@link SpringBootTest} alone
 * (a future {@code @DataJpaTest} slice would pass unseen), and a signature inherited from a base
 * class counts as a violation, because {@link Class#getDeclaredAnnotation} does not look up the
 * hierarchy.
 */
class SpringContextSignatureTest {

  /** Each entry needs a technical reason, stated in the annotation's own Javadoc. */
  private static final List<Class<? extends Annotation>> CANONICAL_SIGNATURES =
      List.of(
          OpaaIntegrationTest.class,
          OpaaMockedChatModelIntegrationTest.class,
          OpaaMockedDocumentServiceIntegrationTest.class,
          OpaaPropertyVariantIntegrationTest.class,
          // The local-auth family (ADR-0033, #1543): the only signatures on the oidc profile, which
          // the four above cannot provide - under local,dev the DevAuthFilter authenticates every
          // request before a bearer token is read, so a local session cannot be driven at all. Each
          // variant states in its own Javadoc why its properties cannot be the family's base.
          OpaaLocalAuthMockMvcTest.class,
          OpaaLocalAuthLinkTest.class,
          OpaaLocalAuthSeedTest.class,
          OpaaLocalAuthRateLimitTest.class);

  @Test
  void everySpringBackedTestUsesOneOfTheCanonicalSignatures() {
    List<String> offenders =
        springBootTestClasses().stream()
            .filter(type -> signatureOf(type) == null)
            .map(Class::getName)
            .toList();

    assertThat(offenders)
        .as(
            "these classes carry an ad-hoc @SpringBootTest instead of one of the canonical"
                + " signatures %s - each such combination costs its own Spring context and its own"
                + " Postgres container (AGENTS.md, \"Spring-Testkontexte\")",
            simpleNames(CANONICAL_SIGNATURES))
        .isEmpty();
  }

  @Test
  void eachCanonicalSignatureStartsExactlyOneContext() {
    Map<MergedContextConfiguration, List<Class<?>>> groups = new LinkedHashMap<>();
    for (Class<?> testClass : springBootTestClasses()) {
      groups.computeIfAbsent(contextCacheKeyOf(testClass), key -> new ArrayList<>()).add(testClass);
    }

    // A signature appearing in more than one group means at least one of its classes carries a
    // differentiator of its own; the report names the classes of every group it appears in.
    List<String> split = new ArrayList<>();
    for (Class<? extends Annotation> signature : CANONICAL_SIGNATURES) {
      List<List<Class<?>>> groupsOfSignature =
          groups.values().stream()
              .filter(members -> members.stream().anyMatch(m -> signature.equals(signatureOf(m))))
              .toList();
      if (groupsOfSignature.size() > 1) {
        split.add(
            signature.getSimpleName()
                + " is split across "
                + groupsOfSignature.size()
                + " contexts: "
                + groupsOfSignature.stream().map(this::names).collect(Collectors.joining(" | ")));
      }
    }

    assertThat(split)
        .as(
            "a class that adds its own @Import/@DynamicPropertySource/@MockitoBean/"
                + "@TestConfiguration to a shared signature leaves its context - move constants into"
                + " the signature's properties, runtime values into one of its initializers and"
                + " replaced beans into OpaaTestBeans (AGENTS.md, \"Spring-Testkontexte\")")
        .isEmpty();

    assertThat(groups.keySet())
        .as(
            "one Spring context and one Postgres container per canonical signature; a further one"
                + " needs a hard technical reason and an entry in CANONICAL_SIGNATURES, not a code"
                + " comment (#1481). Groups: %s",
            groups.values().stream().map(this::names).collect(Collectors.joining(" | ")))
        .hasSize(CANONICAL_SIGNATURES.size());
  }

  /** The exact object Spring's context cache is keyed by - built without starting the context. */
  private MergedContextConfiguration contextCacheKeyOf(Class<?> testClass) {
    return BootstrapUtils.resolveTestContextBootstrapper(testClass)
        .buildMergedContextConfiguration();
  }

  private Class<? extends Annotation> signatureOf(Class<?> testClass) {
    return CANONICAL_SIGNATURES.stream()
        .filter(signature -> testClass.getDeclaredAnnotation(signature) != null)
        .findFirst()
        .orElse(null);
  }

  private String names(List<Class<?>> classes) {
    return classes.stream().map(Class::getSimpleName).sorted().collect(Collectors.joining(", "));
  }

  private List<String> simpleNames(List<Class<? extends Annotation>> types) {
    return types.stream().map(Class::getSimpleName).toList();
  }

  /**
   * Every class of the {@code test} source set that starts a whole application context. {@code
   * io.opaa.integration.*} is excluded exactly as {@code build.gradle.kts} excludes it from the
   * {@code test} task; {@code @WebMvcTest} slices start no application context and no container and
   * are therefore not annotated with {@link SpringBootTest} at all.
   */
  private List<Class<?>> springBootTestClasses() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter((metadataReader, factory) -> true);
    Set<Class<?>> classes = new LinkedHashSet<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("io.opaa")) {
      String name = definition.getBeanClassName();
      if (name == null || name.startsWith("io.opaa.integration.")) {
        continue;
      }
      Class<?> type = load(name);
      if (MergedAnnotations.from(type, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
          .isPresent(SpringBootTest.class)) {
        classes.add(type);
      }
    }
    return classes.stream().sorted(Comparator.comparing(Class::getName)).toList();
  }

  private Class<?> load(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(className, e);
    }
  }
}
