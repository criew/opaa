package io.opaa.sourceaccess;

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
 * {@code io.opaa.sourceaccess} is meant to depend on neither {@code io.opaa.library} nor {@code
 * io.opaa.library}/{@code io.opaa.api} - a source-access primitive (HTTP client construction,
 * redirect following, target-address validation, bounded downloads) has no business knowing about a
 * knowledge library, indexing-internal types or a generated DTO. This test makes that invariant
 * self-checking, so a later change cannot reintroduce such a dependency unnoticed.
 *
 * <p>Modeled after {@link io.opaa.audit.AuditFunnelStructureTest}: reflection over every class this
 * package declares (top-level and nested), checking every field, constructor/method parameter,
 * method return type and supertype/interface against the forbidden package prefixes - not a
 * hardcoded list of "classes that must not exist", so a newly added class is covered automatically.
 *
 * <p><b>What this does not, and cannot, catch</b> (same caveat {@link
 * io.opaa.audit.AuditFunnelStructureTest} documents for its own reflection-based check): a local
 * variable or an expression inside a method body that references a forbidden type without that type
 * ever appearing in a field, parameter or return type signature. Closing that gap completely would
 * need bytecode-level analysis (e.g. ArchUnit); given how small and utility-shaped every class in
 * this package is, the signature-level check above already covers the realistic case - a class
 * reaching into {@code indexing}/{@code library}/{@code api} for a type it does not also accept or
 * return.
 */
class SourceAccessDependencyStructureTest {

  private static final Set<String> FORBIDDEN_PACKAGE_PREFIXES =
      Set.of("io.opaa.indexing", "io.opaa.library", "io.opaa.api");

  @Test
  void sourceAccessImportsNeitherIndexingNorLibraryNorApi() {
    Set<String> offenses = new LinkedHashSet<>();
    for (Class<?> type : allClassesInPackage()) {
      collectOffenses(type, offenses);
    }

    assertThat(offenses)
        .as(
            "io.opaa.sourceaccess must not reference io.opaa.indexing, io.opaa.library or"
                + " io.opaa.api in any field/parameter/return type - a source-access"
                + " primitive has no business knowing about a knowledge library, indexing"
                + " internals or a generated DTO")
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

  /**
   * Every top-level, concrete class directly under {@code io.opaa.sourceaccess} - nested classes
   * (records, enums, exceptions) are reached from there via {@link #collectOffenses}'s own
   * recursion into {@link Class#getDeclaredClasses()}, not enumerated here.
   */
  private List<Class<?>> allClassesInPackage() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
    List<Class<?>> classes = new ArrayList<>();
    for (BeanDefinition beanDefinition : scanner.findCandidateComponents("io.opaa.sourceaccess")) {
      classes.add(loadClass(beanDefinition.getBeanClassName()));
    }
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
