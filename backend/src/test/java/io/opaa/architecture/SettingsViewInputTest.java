package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code SourceConnector#settingsView} shows a library's effective settings - its own merged with
 * the defaults of its profile - so no code that asks for the view reads the library's own stored
 * part ({@code ConnectorData#storedIn}) to hand over. Bytecode only: a stored part reaching the
 * view through a field or another method is not seen.
 */
class SettingsViewInputTest {

  @Test
  void noCallerOfTheSettingsViewHandsItTheOwnStoredPart() {
    assertThat(offenders(MainClasses.get())).isEmpty();
  }

  @Test
  void theCheckFindsACallerHandingTheOwnStoredPart() {
    JavaClasses fixture = new ClassFileImporter().importClasses(OwnPartView.class);

    assertThat(offenders(fixture)).containsExactly(OwnPartView.class.getName() + "#view");
  }

  private static List<String> offenders(JavaClasses classes) {
    return classes.stream()
        .flatMap(javaClass -> javaClass.getCodeUnits().stream())
        .filter(unit -> calls(unit, SourceConnector.class, "settingsView"))
        .filter(unit -> calls(unit, ConnectorData.class, "storedIn"))
        .map(unit -> unit.getOwner().getName() + "#" + unit.getName())
        .toList();
  }

  private static boolean calls(JavaCodeUnit unit, Class<?> owner, String method) {
    return unit.getMethodCallsFromSelf().stream()
        .anyMatch(
            call ->
                call.getName().equals(method)
                    && call.getTargetOwner().isAssignableTo(owner.getName()));
  }

  /** What the check refuses: the view built from the own stored part. */
  static final class OwnPartView {

    ConnectorData view(SourceConnector connector, KnowledgeLibrary library) {
      return connector.settingsView(library, ConnectorData.storedIn(library), true);
    }
  }
}
