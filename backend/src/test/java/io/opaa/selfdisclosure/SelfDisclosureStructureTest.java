package io.opaa.selfdisclosure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.architecture.MainClasses;
import io.opaa.auth.Caller;
import io.opaa.selfdisclosure.web.SelfDisclosureController;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The self-disclosure reads only in the person's own context (docs/features/security-and-
 * compliance.md, "Nur für die Person selbst"): a diagnosis in a foreign rights context ("Sicht
 * als") is one call of {@code ForeignDiagnosticContextService}, not a state of the request, so the
 * guarantee is structural - nothing of the diagnosis reaches the self-disclosure, and no operation
 * of it takes a parameter naming a person.
 */
class SelfDisclosureStructureTest {

  @Test
  void theDiagnosisInAForeignContextNeverReachesTheSelfDisclosure() {
    noClasses()
        .that()
        .resideInAnyPackage("io.opaa.diagnosticaccess..", "io.opaa.searchadmin..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("io.opaa.selfdisclosure..")
        .check(MainClasses.get());
    noClasses()
        .that()
        .resideInAPackage("io.opaa.selfdisclosure..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("io.opaa.diagnosticaccess..", "io.opaa.searchadmin..")
        .check(MainClasses.get());
  }

  @Test
  void noOperationOfTheSelfDisclosureTakesAPersonAsParameter() {
    List<Method> handlers =
        Arrays.stream(SelfDisclosureController.class.getDeclaredMethods())
            .filter(m -> m.getAnnotations().length > 0)
            .filter(
                m ->
                    Arrays.stream(m.getAnnotations())
                        .anyMatch(
                            a ->
                                a.annotationType().isAnnotationPresent(RequestMapping.class)
                                    || a.annotationType() == RequestMapping.class))
            .toList();
    assertThat(handlers).hasSize(3);
    for (Method handler : handlers) {
      for (Parameter parameter : handler.getParameters()) {
        assertThat(parameter.isAnnotationPresent(PathVariable.class))
            .as("%s takes a path variable", handler.getName())
            .isFalse();
        RequestParam param = parameter.getAnnotation(RequestParam.class);
        if (param != null) {
          assertThat(param.value())
              .as("%s takes a request parameter", handler.getName())
              .isEqualTo("ticket");
        }
        if (parameter.getType() == io.opaa.auth.CurrentUser.class) {
          assertThat(parameter.isAnnotationPresent(Caller.class)).isTrue();
        }
      }
    }
  }
}
