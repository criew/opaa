package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The two-stage connection test and the space listing (#1134) against the common test double of
 * both editions: detection without credentials, verification with them, mismatch and refusal
 * reported as results (never as stack traces, never with credentials), and the creation-time
 * edition re-check.
 */
class ConfluenceConnectionServiceTest {

  private static final String EMAIL = "dienst@behoerde.example";
  private static final String TOKEN = "streng-geheim-0815";

  record Deployment(ConfluenceEdition edition, String contextPath) {
    @Override
    public String toString() {
      return edition + (contextPath.isEmpty() ? "" : " under " + contextPath);
    }
  }

  static Stream<Deployment> deployments() {
    return Stream.of(
        new Deployment(ConfluenceEdition.CLOUD, ""),
        new Deployment(ConfluenceEdition.DATA_CENTER, ""),
        new Deployment(ConfluenceEdition.DATA_CENTER, "/confluence"));
  }

  private FakeConfluenceServer server;
  private final ConfluenceConnectionService service =
      new ConfluenceConnectionService(
          new ConfluenceClientFactory(
              new ConfluenceProperties(2, null, null, 0, null, 0, 0, 0, null, null, 0),
              TargetAddressValidator.disabled()));

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  private String start(Deployment deployment) throws IOException {
    server = new FakeConfluenceServer(deployment.edition(), deployment.contextPath());
    server.addSpace("1", "ENG", "Engineering");
    server.addSpace("2", "HR", "Personal");
    server.addSpace("3", "SEC", "Geheimschutz");
    server.addPage(
        "100", "ENG", "Handbuch", null, "<p>x</p>", Instant.parse("2020-01-01T00:00:00Z"));
    server.addToken(EMAIL, TOKEN, Set.of("ENG", "HR", "SEC"));
    return server.baseUrl();
  }

  private static String credentials(ConfluenceEdition edition) {
    return edition == ConfluenceEdition.CLOUD ? EMAIL + ":" + TOKEN : TOKEN;
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void detectsTheEditionWithoutCredentialsAndAsksForTheRightOnes(Deployment deployment)
      throws Exception {
    String url =
        start(deployment) + (deployment.edition() == ConfluenceEdition.CLOUD ? "/wiki" : "");

    ConfluenceConnectionService.Probe probe = service.probe(url, null, null);

    assertThat(probe.reachable()).isTrue();
    assertThat(probe.detectedEdition()).isEqualTo(deployment.edition());
    assertThat(probe.credentialsVerified()).isFalse();
    assertThat(probe.readableSpaces()).isNull();
    assertThat(probe.message())
        .contains(
            deployment.edition() == ConfluenceEdition.CLOUD
                ? "E-Mail-Adresse und API-Token"
                : "Personal Access Token");
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void verifiesCredentialsAndCountsReadableSpaces(Deployment deployment) throws Exception {
    String url = start(deployment);

    ConfluenceConnectionService.Probe probe =
        service.probe(url, credentials(deployment.edition()), deployment.edition());

    assertThat(probe.reachable()).isTrue();
    assertThat(probe.credentialsVerified()).isTrue();
    // one authenticated request, never a full pagination - the listing endpoint does that
    assertThat(probe.readableSpaces()).isNull();
    assertThat(probe.message())
        .contains("Zugangsdaten gültig")
        .doesNotContain(TOKEN)
        .doesNotContain(EMAIL);
    // Cloud probes the space listing, Data Center the current user - one request either way
    assertThat(
            server.requests().stream()
                .filter(r -> r.contains("space") || r.contains("user/current"))
                .count())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void reportsRefusedCredentialsMismatchedEditionAndWrongFormatAsResults(Deployment deployment)
      throws Exception {
    String url = start(deployment);
    ConfluenceEdition other =
        deployment.edition() == ConfluenceEdition.CLOUD
            ? ConfluenceEdition.DATA_CENTER
            : ConfluenceEdition.CLOUD;

    ConfluenceConnectionService.Probe refused =
        service.probe(url, credentials(deployment.edition()) + "x", null);
    assertThat(refused.reachable()).isFalse();
    assertThat(refused.detectedEdition()).isEqualTo(deployment.edition());
    assertThat(refused.credentialsVerified()).isFalse();
    assertThat(refused.message())
        .contains(deployment.edition() == ConfluenceEdition.CLOUD ? "401" : "anonym")
        .doesNotContain(TOKEN);

    ConfluenceConnectionService.Probe mismatch =
        service.probe(url, credentials(deployment.edition()), other);
    assertThat(mismatch.reachable()).isFalse();
    assertThat(mismatch.detectedEdition()).isEqualTo(deployment.edition());
    assertThat(mismatch.message()).contains("nicht");

    String wrongFormat = deployment.edition() == ConfluenceEdition.CLOUD ? "nur-token" : "a:b";
    ConfluenceConnectionService.Probe badFormat = service.probe(url, wrongFormat, null);
    assertThat(badFormat.reachable()).isFalse();
    assertThat(badFormat.detectedEdition()).isEqualTo(deployment.edition());
    assertThat(badFormat.credentialsVerified()).isFalse();
  }

  @Test
  void noConfluenceAndBadAddressesAreTold() throws Exception {
    ConfluenceConnectionService.Probe probe =
        service.probe("http://127.0.0.1:9/nichts", null, null);
    assertThat(probe.reachable()).isFalse();
    assertThat(probe.detectedEdition()).isNull();

    assertThatThrownBy(() -> service.probe("kein-url", null, null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.probe("https://a:b@wiki.example.org", null, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zugangsdaten");
  }

  @Test
  void blockedTargetNamesTheAllowlistInTheResult() throws Exception {
    ConfluenceConnectionService strict =
        new ConfluenceConnectionService(
            new ConfluenceClientFactory(
                ConfluenceProperties.defaults(), new TargetAddressValidator(true, List.of())));
    String url = start(new Deployment(ConfluenceEdition.DATA_CENTER, ""));

    ConfluenceConnectionService.Probe probe = strict.probe(url, null, null);

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.message())
        .contains("gesperrten Adressbereich")
        .contains("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
    assertThatThrownBy(() -> strict.requireEdition(url, ConfluenceEdition.DATA_CENTER))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void listsOnlyReadableSpacesAcrossPages(Deployment deployment) throws Exception {
    String url = start(deployment);

    List<ConfluenceSpace> spaces =
        service.listSpaces(url, deployment.edition(), credentials(deployment.edition()));

    assertThat(spaces).extracting(ConfluenceSpace::key).containsExactly("ENG", "HR", "SEC");
    assertThat(spaces)
        .extracting(ConfluenceSpace::name)
        .containsExactly("Engineering", "Personal", "Geheimschutz");
    assertThat(server.requests().stream().filter(r -> r.contains("space")).count())
        .as("three spaces at page size two need two pages")
        .isGreaterThanOrEqualTo(2);

    assertThatThrownBy(
            () ->
                service.listSpaces(
                    url, deployment.edition(), credentials(deployment.edition()) + "x"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining(deployment.edition() == ConfluenceEdition.CLOUD ? "401" : "anonym")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    assertThatThrownBy(() -> service.listSpaces(url, null, TOKEN))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings.edition");
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void creationReCheckAcceptsTheRealEditionAndRefusesTheOther(Deployment deployment)
      throws Exception {
    String url = start(deployment);
    ConfluenceEdition other =
        deployment.edition() == ConfluenceEdition.CLOUD
            ? ConfluenceEdition.DATA_CENTER
            : ConfluenceEdition.CLOUD;

    service.requireEdition(url, deployment.edition());

    assertThatThrownBy(() -> service.requireEdition(url, other))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("erkannt, nicht gewählt");
    server.close();
    assertThatThrownBy(() -> service.requireEdition(url, deployment.edition()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("konnte nicht bestätigt werden");
  }
}
