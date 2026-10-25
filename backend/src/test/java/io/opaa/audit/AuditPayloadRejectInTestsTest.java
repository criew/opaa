package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

/**
 * Every Spring context of the test suite refuses an audit payload key the catalog does not list,
 * whichever profiles it activates: the profile sets come from every {@code @ActiveProfiles} under
 * {@code src/test/java}, plus none at all.
 */
class AuditPayloadRejectInTestsTest {

  private static final Pattern ACTIVE_PROFILES =
      Pattern.compile("@ActiveProfiles\\(\\s*(?:value\\s*=\\s*)?\\{?([^})]*)\\}?\\s*\\)");

  private static final Pattern PROFILE = Pattern.compile("\"([^\"]+)\"");

  @Test
  void theSuiteActivatesTheProfilesThisTestCovers() {
    assertThat(profileSets()).contains("", "oidc", "dev", "test");
  }

  @ParameterizedTest
  @MethodSource("profileSets")
  void theWriteIsRefusedUnderEveryProfileSetOfTheSuite(String profiles) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(",", -1));

    ConfigDataEnvironmentPostProcessor.applyTo(environment);

    assertThat(environment.getProperty("opaa.audit.payload.reject-unlisted", Boolean.class))
        .isTrue();
  }

  static Set<String> profileSets() {
    Set<String> sets = new TreeSet<>();
    sets.add("");
    try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        Matcher annotation = ACTIVE_PROFILES.matcher(Files.readString(file));
        while (annotation.find()) {
          List<String> profiles = new ArrayList<>();
          Matcher profile = PROFILE.matcher(annotation.group(1));
          while (profile.find()) {
            profiles.add(profile.group(1));
          }
          sets.add(String.join(",", profiles));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sets;
  }
}
