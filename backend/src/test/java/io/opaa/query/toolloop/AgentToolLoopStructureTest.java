package io.opaa.query.toolloop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import io.opaa.architecture.MainClasses;
import org.junit.jupiter.api.Test;

/**
 * Reads of the tool loop never reach the audit log (ADR-0070, Entscheidung 4: store (c) holds no
 * reads): the guarantee is structural, the loop and its tools do not know the audit at all.
 */
class AgentToolLoopStructureTest {

  @Test
  void theToolLoopWritesNothingToTheAuditLog() {
    noClasses()
        .that()
        .resideInAPackage("io.opaa.query.toolloop..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("io.opaa.audit..")
        .check(MainClasses.get());
  }
}
