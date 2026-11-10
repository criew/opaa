package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A document template is filled in the sandbox alone, never in the backend process: a template
 * engine evaluating a user's tags here would let a template run code beside the data (#3673). So no
 * main class may use a template or script engine, except the mail templates the system
 * administration writes ({@code io.opaa.mail.MailTemplateService}, logic-less Mustache).
 */
class NoTemplateEngineTest {

  private static final Set<String> ENGINE_PACKAGES =
      Set.of(
          "com.samskivert.mustache",
          "com.github.mustachejava",
          "com.hubspot.jinjava",
          "io.pebbletemplates",
          "freemarker",
          "org.thymeleaf",
          "org.apache.velocity",
          "groovy.text",
          "org.stringtemplate",
          "javax.script",
          "org.graalvm.polyglot",
          "org.springframework.expression");

  private static final Set<String> ADMITTED = Set.of("io.opaa.mail.MailTemplateService");

  @Test
  void noTemplateOfAUserIsRenderedInTheBackend() {
    Set<String> uses = new TreeSet<>();
    for (JavaClass type : MainClasses.get()) {
      boolean admitted = ADMITTED.contains(type.getName());
      for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
        String target = dependency.getTargetClass().getPackageName();
        boolean engine =
            ENGINE_PACKAGES.stream()
                .anyMatch(prefix -> target.equals(prefix) || target.startsWith(prefix + "."));
        boolean templatePackage = type.getPackageName().startsWith("io.opaa.template");
        if (engine && (!admitted || templatePackage)) {
          uses.add(type.getName() + " -> " + dependency.getTargetClass().getName());
        }
      }
    }

    assertThat(uses)
        .as("templates are filled in the sandbox only; no template or script engine here")
        .isEmpty();
  }
}
