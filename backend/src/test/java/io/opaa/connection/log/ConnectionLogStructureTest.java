package io.opaa.connection.log;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import io.opaa.architecture.MainClasses;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Only {@link ConnectionLog} writes the connection log, in the manner of {@code
 * AuditFunnelStructureTest}: it alone creates entries and calls a writing method of the repository,
 * and the repository is held only by the writer, the reader and the retention.
 */
class ConnectionLogStructureTest {

  private static final JavaClasses MAIN = MainClasses.get();

  private static final DescribedPredicate<JavaMethodCall> A_WRITE_OF_THE_REPOSITORY =
      DescribedPredicate.describe(
          "a writing method of ConnectionLogRepository",
          call ->
              call.getTargetOwner().isAssignableTo(ConnectionLogRepository.class)
                  && isWrite(call.getName()));

  @Test
  void onlyTheWriterCallsAWritingMethodOfTheRepository() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ConnectionLog.class.getName())
        .should()
        .callMethodWhere(A_WRITE_OF_THE_REPOSITORY)
        .check(MAIN);
    assertThat(callersOf(A_WRITE_OF_THE_REPOSITORY))
        .as("the rule above must see the writer's own call")
        .containsExactly(ConnectionLog.class.getName());
  }

  @Test
  void onlyTheWriterCreatesAnEntry() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ConnectionLog.class.getName())
        .should()
        .callConstructorWhere(
            DescribedPredicate.describe(
                "a constructor of ConnectionLogEntry",
                call -> call.getTargetOwner().isEquivalentTo(ConnectionLogEntry.class)))
        .check(MAIN);
  }

  @Test
  void theRepositoryIsHeldOnlyByWriterReaderAndRetention() {
    Set<String> holders =
        MAIN.get(ConnectionLogRepository.class).getDirectDependenciesToSelf().stream()
            .map(dependency -> dependency.getOriginClass().getName())
            .collect(Collectors.toSet());

    assertThat(holders)
        .containsExactlyInAnyOrder(
            ConnectionLog.class.getName(),
            ConnectionLogQueryService.class.getName(),
            ConnectionLogRetention.class.getName());
  }

  /** Any writing method; the retention's call of the deletion function is its own path. */
  private static boolean isWrite(String method) {
    return !method.equals("deleteExpiredPartitions") && isWriteName(method);
  }

  private static boolean isWriteName(String method) {
    return method.startsWith("save")
        || method.startsWith("delete")
        || method.startsWith("insert")
        || method.startsWith("update");
  }

  private static Set<String> callersOf(DescribedPredicate<JavaMethodCall> access) {
    return MAIN.stream()
        .filter(javaClass -> javaClass.getMethodCallsFromSelf().stream().anyMatch(access))
        .map(JavaClass::getName)
        .collect(Collectors.toSet());
  }
}
