package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.AuditController;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * PR #450 review, finding 1: {@code everyAccessPathSelfLogsOnSuccessAndOnDenial} (in {@link
 * AuditQueryServiceIntegrationTest}) only proves the five known #393 access paths self-log - it
 * cannot prove that {@link AuditLogRepository} is unreachable any other way, and the issue's own
 * acceptance criterion asks for exactly that: "ein Test belegt, dass kein Aufrufweg existiert, der
 * Protokolldaten liest, ohne einen Eintrag zu erzeugen". {@link AuditLogRepository} is now
 * package-private (closing the reachability gap for every class outside {@code io.opaa.audit} at
 * compile time - see that interface's own Javadoc), and this test closes the remaining gap
 * structurally for classes inside the package too: it scans every Spring stereotype-annotated class
 * on the classpath, not a hardcoded name list (the same "closed set, not an enumeration" pattern
 * {@link AuditQueryServiceIntegrationTest#noAccessPathAcceptsOrSortsByActor} already uses), and
 * fails the moment a new class other than {@link AuditQueryService} or {@link AuditLogService}
 * holds a reference to {@link AuditLogRepository} - whether as a field or a constructor parameter.
 *
 * <p>{@link #auditControllerHoldsOnlyTheQueryServiceAsItsAuditReadDependency()} makes the matching
 * claim one layer up, at the HTTP entry point: {@link AuditController} must depend on {@link
 * AuditQueryService} for reads and never directly on {@link AuditLogRepository} or {@link
 * AuditLogService} - a future endpoint that skipped the funnel by reaching one of those two
 * directly would otherwise compile and run without a single failing test.
 */
class AuditFunnelStructureTest {

  /**
   * The only two classes {@link AuditLogRepository}'s own Javadoc names as legitimate holders of a
   * reference to it - the funnel's read path ({@link AuditQueryService}) and its write path ({@link
   * AuditLogService}, used by {@link AuditEventRecorder} and every #392 event-emitting service
   * indirectly, never by holding {@link AuditLogRepository} itself).
   */
  private static final Set<Class<?>> ALLOWED_AUDIT_LOG_REPOSITORY_HOLDERS =
      Set.of(AuditQueryService.class, AuditLogService.class);

  @Test
  void auditLogRepositoryIsHeldOnlyByTheFunnelsReadAndWritePath() {
    Set<Class<?>> offendingClasses = new LinkedHashSet<>();

    for (Class<?> candidate : scanSpringStereotypeAnnotatedClasses()) {
      if (declaresAuditLogRepositoryReference(candidate)
          && !ALLOWED_AUDIT_LOG_REPOSITORY_HOLDERS.contains(candidate)) {
        offendingClasses.add(candidate);
      }
    }

    assertThat(offendingClasses)
        .as(
            "Only %s may hold a reference to AuditLogRepository (field or constructor parameter) -"
                + " every other Spring-managed class must go through AuditQueryService instead"
                + " (#394, PR #450 review, finding 1)",
            ALLOWED_AUDIT_LOG_REPOSITORY_HOLDERS)
        .isEmpty();
  }

  @Test
  void auditControllerHoldsOnlyTheQueryServiceAsItsAuditReadDependency() {
    boolean holdsQueryService = false;
    for (Field field : AuditController.class.getDeclaredFields()) {
      if (field.getType() == AuditQueryService.class) {
        holdsQueryService = true;
      }
      assertThat(field.getType())
          .as(
              "AuditController must never hold AuditLogRepository or AuditLogService directly -"
                  + " every read has to go through AuditQueryService (#394, PR #450 review,"
                  + " finding 1)")
          .isNotIn(AuditLogRepository.class, AuditLogService.class);
    }

    assertThat(holdsQueryService)
        .as("AuditController must hold AuditQueryService - otherwise this test proves nothing")
        .isTrue();
  }

  private boolean declaresAuditLogRepositoryReference(Class<?> candidate) {
    for (Field field : candidate.getDeclaredFields()) {
      if (field.getType() == AuditLogRepository.class) {
        return true;
      }
    }
    for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
      for (Class<?> parameterType : constructor.getParameterTypes()) {
        if (parameterType == AuditLogRepository.class) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Every {@code @Component}-meta-annotated class under {@code io.opaa} - covers
   * {@code @Service}/{@code @RestController}/{@code @Repository}/{@code @Controller} the same way
   * Spring's own component scan does, so a future class does not need to be added to any list here
   * to be covered.
   */
  private List<Class<?>> scanSpringStereotypeAnnotatedClasses() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
    return scanner.findCandidateComponents("io.opaa").stream()
        .<Class<?>>map(beanDefinition -> loadClass(beanDefinition.getBeanClassName()))
        .toList();
  }

  private Class<?> loadClass(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
