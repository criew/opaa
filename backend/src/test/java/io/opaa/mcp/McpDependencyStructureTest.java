package io.opaa.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * The invariant ADR-0035, Entscheidung 5 demands of the MCP layer: it is a translation layer with
 * <b>no second way to the data</b>. Sibling of {@link
 * io.opaa.search.SearchDependencyStructureTest}, with the same caveat - a local variable inside a
 * method body is not caught; at this package's size the signature-level check covers the realistic
 * case.
 *
 * <p>Four forbidden directions, for four reasons. A vector store or the retrieval internals would
 * be a <b>second ranking path</b>; an answer generator would make this the assistant interface the
 * channel explicitly is not; a repository of the index would be a <b>second read path past the
 * permission filter</b>; and {@code io.opaa.library} or its holdings in {@code io.opaa.knowledge}
 * would be the second place the effective view is formed - the one place is {@code
 * io.opaa.search.SearchScopeSource}.
 */
class McpDependencyStructureTest {

  private static final Set<String> FORBIDDEN_PACKAGE_PREFIXES =
      Set.of(
          "org.springframework.ai.vectorstore",
          "io.opaa.query.retrieval",
          "io.opaa.query.answer",
          "io.opaa.query.citation",
          "io.opaa.indexing.chunk",
          "io.opaa.indexing.document",
          "io.opaa.knowledge",
          "io.opaa.library",
          "io.opaa.searchadmin");

  @Test
  void theMcpLayerReachesTheDataOnlyThroughTheDomainServicesOfTheSearch() {
    Set<String> offenses = new LinkedHashSet<>();
    for (Class<?> type : allClassesInPackage()) {
      collectOffenses(type, offenses);
    }

    assertThat(offenses)
        .as(
            "io.opaa.mcp must reach the holdings only through io.opaa.search: a second read path"
                + " or a second place forming the effective view would be a second permission"
                + " truth, and of two permission checks one is wrong after two years (ADR-0035,"
                + " Entscheidung 5)")
        .isEmpty();
  }

  private void collectOffenses(Class<?> type, Set<String> offenses) {
    for (Field field : type.getDeclaredFields()) {
      checkType(type, field.getType(), offenses);
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      checkParameters(type, constructor, offenses);
    }
    for (Method method : type.getDeclaredMethods()) {
      checkParameters(type, method, offenses);
      checkType(type, method.getReturnType(), offenses);
    }
    if (type.getSuperclass() != null) {
      checkType(type, type.getSuperclass(), offenses);
    }
    for (Class<?> implementedInterface : type.getInterfaces()) {
      checkType(type, implementedInterface, offenses);
    }
    for (Class<?> nested : type.getDeclaredClasses()) {
      collectOffenses(nested, offenses);
    }
  }

  private void checkParameters(Class<?> owner, Executable executable, Set<String> offenses) {
    for (Class<?> parameterType : executable.getParameterTypes()) {
      checkType(owner, parameterType, offenses);
    }
  }

  private void checkType(Class<?> owner, Class<?> referenced, Set<String> offenses) {
    String packageName = referenced.getPackageName();
    for (String forbidden : FORBIDDEN_PACKAGE_PREFIXES) {
      if (packageName.equals(forbidden) || packageName.startsWith(forbidden + ".")) {
        offenses.add(owner.getName() + " -> " + referenced.getName());
      }
    }
  }

  private List<Class<?>> allClassesInPackage() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
    List<Class<?>> classes = new ArrayList<>();
    for (BeanDefinition beanDefinition : scanner.findCandidateComponents("io.opaa.mcp")) {
      String className = beanDefinition.getBeanClassName();
      // The production classes only: this package's own test writes its fixtures through the
      // indexing types, and the invariant is about the shipped code.
      if (className.endsWith("Test")) {
        continue;
      }
      classes.add(loadClass(className));
    }
    assertThat(classes).as("the scan must actually find the package's classes").isNotEmpty();
    return classes;
  }

  private Class<?> loadClass(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
