package io.opaa.search;

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
 * The invariant ADR-0035, Entscheidung 5 demands be held by a structural test rather than by
 * discipline: the reading path has exactly one way to the data, and it is a domain service.
 *
 * <p>Two forbidden directions, for two different reasons. {@code
 * org.springframework.ai.vectorstore} and {@code io.opaa.query.retrieval} would be a <b>second
 * ranking path</b> - retrieval belongs behind {@code io.opaa.query.KnowledgeRetrieval}, the one
 * entrance {@code POST /api/v1/query} uses as well, so the two cannot drift apart. {@code
 * io.opaa.query.answer} would be a <b>generation call</b> - the whole point of this path is that
 * the foreign tool formulates the answer.
 *
 * <p>This replaces the prompt-marker assertion as the <em>proof</em> of "no generation": that one
 * compares a copied snippet of {@code AnswerGenerationService}'s system prompt and would silently
 * stop checking anything if the prompt were reworded. It stays as a supplement, not as the barrier.
 *
 * <p>Modeled after {@link io.opaa.sourceaccess.SourceAccessDependencyStructureTest}, including its
 * caveat: a local variable inside a method body that references a forbidden type without it
 * appearing in a field, parameter or return type is not caught. Closing that gap would need
 * bytecode analysis; at the size of this package the signature-level check covers the realistic
 * case - a class reaching for a vector store or an answer generator it also stores or returns.
 */
class SearchDependencyStructureTest {

  private static final Set<String> FORBIDDEN_PACKAGE_PREFIXES =
      Set.of(
          "org.springframework.ai.vectorstore",
          "io.opaa.query.retrieval",
          "io.opaa.query.answer",
          "io.opaa.query.citation");

  @Test
  void searchRanksNothingItselfAndGeneratesNothing() {
    Set<String> offenses = new LinkedHashSet<>();
    for (Class<?> type : allClassesInPackage()) {
      collectOffenses(type, offenses);
    }

    assertThat(offenses)
        .as(
            "io.opaa.search must reach the index only through io.opaa.query.KnowledgeRetrieval and"
                + " must never reference an answer generator: a second ranking path would be a"
                + " second quality truth, and a generation call would make this the assistant"
                + " interface it is explicitly not (ADR-0035, Entscheidung 5)")
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
    for (BeanDefinition beanDefinition : scanner.findCandidateComponents("io.opaa.search")) {
      String className = beanDefinition.getBeanClassName();
      // The production classes only: this package's own tests legitimately reach for a VectorStore
      // to write their fixtures, and the invariant is about the shipped code.
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
