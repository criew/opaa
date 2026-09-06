package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.s3.FakeS3ObjectStore;
import io.opaa.indexing.source.s3.S3AccessException;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3Connection;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The paths a real store cannot easily produce (ADR-0027, #1376): the caller's own mistakes as a
 * 400 (no settings, an address that is no endpoint, a proxy without port), a missing or malformed
 * key as a result, a blocked or unreachable endpoint as a result with the access layer's message,
 * the TLS hint, and the {@code NotPermitted} fallback of the bucket listing.
 */
class S3ConnectionServiceTest {

  private static final S3SourceSettings SETTINGS =
      new S3SourceSettings(
          "eu-central-1", true, List.of(S3Scope.of("dokumente", "2025/")), null, null);

  private S3ClientFactory factory;
  private S3ConnectionService service;

  @BeforeEach
  void setUp() {
    factory = mock(S3ClientFactory.class);
    service = new S3ConnectionService(factory);
  }

  @Test
  void aCallerMistakeIsA400() {
    assertThatThrownBy(() -> service.probe("https://s3.example.org", null, "ak:sk", false, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("s3Settings");
    assertThatThrownBy(() -> service.probe("s3.example.org", null, "ak:sk", false, SETTINGS))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("http://");
    assertThatThrownBy(
            () -> service.probe("https://s3.example.org", "proxy:abc", "ak:sk", false, SETTINGS))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("host:port");
    assertThatThrownBy(
            () -> service.listBuckets("https://s3.example.org", null, "nur-key", false, null, true))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("accessKey:secretKey");
  }

  @Test
  void aMissingOrMalformedKeyIsAResultNotAnException() throws Exception {
    S3ConnectionService.Probe missing =
        service.probe("https://s3.example.org", null, null, false, SETTINGS);
    assertThat(missing.reachable()).isFalse();
    assertThat(missing.credentialsVerified()).isFalse();
    assertThat(missing.message()).contains("Zugangsdaten erforderlich");

    S3ConnectionService.Probe malformed =
        service.probe("https://s3.example.org", null, "nur-access-key", false, SETTINGS);
    assertThat(malformed.reachable()).isFalse();
    assertThat(malformed.message()).contains("accessKey:secretKey");
  }

  @Test
  void aBlockedOrUnreachableEndpointIsAResultWithTheAccessLayersMessage() throws Exception {
    when(factory.createForProbe(any(), anyCollection()))
        .thenThrow(new S3AccessException.TargetBlocked("Die Zieladresse 10.0.0.5 ist gesperrt."));

    S3ConnectionService.Probe probe =
        service.probe("http://10.0.0.5:9000", null, "ak:sk", false, SETTINGS);

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.credentialsVerified()).isFalse();
    assertThat(probe.message())
        .contains("10.0.0.5")
        .contains("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
    assertThat(probe.scopes()).isEmpty();
  }

  @Test
  void theTlsDiagnosisNamesTheSwitchAsTheLastOption() throws Exception {
    when(factory.createForProbe(any(), anyCollection())).thenThrow(new S3AccessException.Tls());

    S3ConnectionService.Probe probe =
        service.probe("https://minio.intern:9000", null, "ak:sk", false, SETTINGS);

    assertThat(probe.message())
        .contains("TLS-Verbindung")
        .contains("als letzte Option die TLS-Prüfung ausgesetzt");
  }

  @Test
  void theConnectionCarriesEndpointRegionStyleProxyAndTlsAndTheScopes() throws Exception {
    FakeS3ObjectStore store =
        new FakeS3ObjectStore().put("dokumente", "2025/a.pdf", "A", "application/pdf");
    when(factory.createForProbe(any(), anyCollection())).thenReturn(store);

    S3ConnectionService.Probe probe =
        service.probe(
            "HTTPS://Minio.Intern:9000/", "proxy.intern:3128", "ak:sk:token", true, SETTINGS);

    ArgumentCaptor<S3Connection> connection = ArgumentCaptor.forClass(S3Connection.class);
    ArgumentCaptor<java.util.Collection<S3Scope>> scopes =
        ArgumentCaptor.forClass(java.util.Collection.class);
    org.mockito.Mockito.verify(factory).createForProbe(connection.capture(), scopes.capture());
    assertThat(connection.getValue().endpoint().toString()).isEqualTo("https://minio.intern:9000");
    assertThat(connection.getValue().region()).isEqualTo("eu-central-1");
    assertThat(connection.getValue().pathStyle()).isTrue();
    assertThat(connection.getValue().proxyHost()).isEqualTo("proxy.intern");
    assertThat(connection.getValue().proxyPort()).isEqualTo(3128);
    assertThat(connection.getValue().insecureSsl()).isTrue();
    assertThat(connection.getValue().credentials().hasSessionToken()).isTrue();
    assertThat(scopes.getValue()).containsExactly(S3Scope.of("dokumente", "2025/"));
    assertThat(probe.reachable()).isTrue();
    assertThat(probe.message()).contains("Der Bereich ist erreichbar").contains("1 Objekt");
    assertThat(probe.objectCount()).isEqualTo(1);
    assertThat(store.isClosed()).as("the probe's store is closed").isTrue();
  }

  @Test
  void anEmptyScopePassesButSaysTheReadRightWasNotProbed() throws Exception {
    when(factory.createForProbe(any(), anyCollection()))
        .thenReturn(new FakeS3ObjectStore().bucket("dokumente"));

    S3ConnectionService.Probe probe =
        service.probe("https://s3.example.org", null, "ak:sk", false, SETTINGS);

    assertThat(probe.reachable()).isTrue();
    assertThat(probe.scopes().get(0).readAllowed()).isNull();
    assertThat(probe.objectCount()).isZero();
    assertThat(probe.message())
        .contains("0 Objekte")
        .contains("Leserecht konnte mangels Objekt nicht geprüft werden")
        .doesNotContain("Lesen sind erlaubt");
  }

  @Test
  void theVerdictFollowsTheFailureTypesNotTheMessageText() throws Exception {
    // a key that may list but not read an object whose name contains the refusal phrase: the
    // scope fails on s3:GetObject, the key itself is verified
    FakeS3ObjectStore store =
        new FakeS3ObjectStore()
            .put("dokumente", "2025/Zugangsdaten abgelehnt.pdf", "A", "application/pdf")
            .failRead(
                "dokumente",
                "2025/Zugangsdaten abgelehnt.pdf",
                () ->
                    new S3AccessException.ReadForbidden(
                        "dokumente", "2025/Zugangsdaten abgelehnt.pdf"));
    when(factory.createForProbe(any(), anyCollection())).thenReturn(store);

    S3ConnectionService.Probe probe =
        service.probe("https://s3.example.org", null, "ak:sk", false, SETTINGS);

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.credentialsVerified()).isTrue();
    assertThat(probe.message()).startsWith("Bereich „dokumente/2025/“").contains("s3:GetObject");

    // a missing bucket presupposes an accepted signature: verified, not reachable
    when(factory.createForProbe(any(), anyCollection()))
        .thenReturn(new FakeS3ObjectStore().bucket("anderer"));
    S3ConnectionService.Probe missing =
        service.probe("https://s3.example.org", null, "ak:sk", false, SETTINGS);
    assertThat(missing.reachable()).isFalse();
    assertThat(missing.credentialsVerified()).isTrue();
    assertThat(missing.scopes().get(0).bucketReachable()).isFalse();
    assertThat(missing.message()).contains("existiert nicht");

    // a refused key ends the test as such, whatever the other scopes say
    S3SourceSettings two =
        new S3SourceSettings(
            null, true, List.of(S3Scope.of("dokumente", ""), S3Scope.of("archiv", "")), null, null);
    when(factory.createForProbe(any(), anyCollection()))
        .thenReturn(
            new FakeS3ObjectStore()
                .bucket("dokumente")
                .failBucket(
                    "archiv", () -> new S3AccessException.Authentication("InvalidAccessKeyId")));
    S3ConnectionService.Probe refused =
        service.probe("https://s3.example.org", null, "ak:sk", false, two);
    assertThat(refused.reachable()).isFalse();
    assertThat(refused.credentialsVerified()).isFalse();
    assertThat(refused.message()).contains("Zugangsdaten abgelehnt");
    assertThat(refused.scopes()).hasSize(2);
  }

  @Test
  void aRedirectToAnotherRegionReachesTheScopeAndTheSummary() throws Exception {
    when(factory.createForProbe(any(), anyCollection()))
        .thenReturn(
            new FakeS3ObjectStore()
                .failBucket(
                    "dokumente", () -> new S3AccessException.WrongRegionOrStyle("dokumente")));

    S3ConnectionService.Probe probe =
        service.probe("https://s3.example.org", null, "ak:sk", false, SETTINGS);

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.credentialsVerified()).isTrue();
    assertThat(probe.scopes().get(0).message()).contains("Region oder Adressstil");
    assertThat(probe.message()).contains("Region oder Adressstil");
  }

  @Test
  void scopesBeyondTheDeadlineAreReportedAsNotProbed() throws Exception {
    java.time.Instant start = java.time.Instant.parse("2026-09-06T10:00:00Z");
    java.util.Iterator<java.time.Instant> ticks =
        List.of(start, start.plus(S3ConnectionService.PROBE_DEADLINE).plusSeconds(1)).iterator();
    java.time.Clock clock = mock(java.time.Clock.class);
    when(clock.instant()).thenAnswer(invocation -> ticks.next());
    S3ConnectionService bounded = new S3ConnectionService(factory, clock);
    FakeS3ObjectStore store = new FakeS3ObjectStore().bucket("dokumente").bucket("archiv");
    when(factory.createForProbe(any(), anyCollection())).thenReturn(store);
    S3SourceSettings two =
        new S3SourceSettings(
            null, true, List.of(S3Scope.of("dokumente", ""), S3Scope.of("archiv", "")), null, null);

    S3ConnectionService.Probe probe =
        bounded.probe("https://s3.example.org", null, "ak:sk", false, two);

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.scopes().get(0).passed()).isTrue();
    assertThat(probe.scopes().get(1).message()).isEqualTo(S3ConnectionService.NOT_PROBED);
    assertThat(probe.message()).contains("archiv").contains("Zeitlimit");
    assertThat(store.calls()).noneMatch(call -> call.contains("archiv"));
  }

  @Test
  void aProxyWithAnUnusablePortIsAGerman400() {
    assertThatThrownBy(
            () ->
                service.probe("https://s3.example.org", "proxy.intern:0", "ak:sk", false, SETTINGS))
        .isInstanceOf(ValidationException.class)
        .hasMessage(io.opaa.sourceaccess.ProxyAndCredentials.INVALID_PROXY_MESSAGE);
    assertThatThrownBy(
            () ->
                service.listBuckets(
                    "https://s3.example.org", "proxy.intern:70000", "ak:sk", false, null, true))
        .isInstanceOf(ValidationException.class)
        .hasMessage(io.opaa.sourceaccess.ProxyAndCredentials.INVALID_PROXY_MESSAGE);
  }

  @Test
  void aKeyThatMayNotListBucketsGetsTheFallbackNotAnError() throws Exception {
    when(factory.createForProbe(any(), anyCollection()))
        .thenReturn(new FakeS3ObjectStore().bucket("dokumente").bucketListingPermitted(false));

    S3BucketListResult result =
        service.listBuckets("https://s3.example.org", null, "ak:sk", false, null, false);

    assertThat(result.permitted()).isFalse();
    assertThat(result.buckets()).isEmpty();
    assertThat(result.message()).contains("s3:ListAllMyBuckets").contains("von Hand");

    when(factory.createForProbe(any(), anyCollection()))
        .thenReturn(new FakeS3ObjectStore().bucket("dokumente").bucket("satzungen"));
    S3BucketListResult listed =
        service.listBuckets("https://s3.example.org", null, "ak:sk", false, "eu-west-1", false);
    assertThat(listed.permitted()).isTrue();
    assertThat(listed.buckets()).containsExactly("dokumente", "satzungen");
    assertThat(listed.message()).isNull();
  }
}
