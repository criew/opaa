package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.s3.S3AccessException;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The scope "Alle Buckets": only for a key that may list the buckets, never with a selection. */
class S3AllBucketsTest {

  private static final String ENDPOINT = "https://minio.intern.example:9000";

  private final S3ConnectionService connectionService = mock(S3ConnectionService.class);
  private final S3SourceConnector connector =
      new S3SourceConnector(
          connectionService,
          new S3ClientFactory(S3Properties.defaults(), TargetAddressValidator.disabled()),
          mock(SourceSyncStateRepository.class),
          mock(S3OriginalAccess.class),
          mock(io.opaa.indexing.source.s3.events.S3EventService.class));

  private static SourceSettings request(Map<String, Object> settings) {
    return new SourceSettings(null, ENDPOINT, "ak:sk", ConnectorData.of(settings));
  }

  // regression guard for #3117: an empty scope list was the only way to say "everything" and
  // refused
  @Test
  void allBucketsIsAcceptedWhenTheKeyMayListTheBuckets() throws Exception {
    when(connectionService.listBuckets(anyString(), anyString(), any(), anyBoolean()))
        .thenReturn(new S3BucketListResult(true, List.of("dokumente"), null));

    SourceSettings validated =
        connector.validate(request(Map.of("pathStyle", true, "allBuckets", true)));

    assertThat(validated.connectorSettings().asMap())
        .containsEntry("allBuckets", true)
        .containsEntry("scopes", List.of());
  }

  @Test
  void allBucketsIsRefusedWithoutListBuckets() throws Exception {
    when(connectionService.listBuckets(anyString(), anyString(), any(), anyBoolean()))
        .thenReturn(new S3BucketListResult(false, List.of(), "fehlt"));

    assertThatThrownBy(() -> connector.validate(request(Map.of("allBuckets", true))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ListAllMyBuckets");
  }

  @Test
  void allBucketsAndAScopeSelectionAreRefusedTogether() {
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(
                        Map.of(
                            "allBuckets",
                            true,
                            "scopes",
                            List.of(Map.of("bucket", "dokumente", "prefix", ""))))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("allBuckets");
  }

  @Test
  void theConnectionTestNamesTheBucketsTheLibraryLeavesOut() throws Exception {
    when(connectionService.resolveAllBuckets(anyString(), anyString(), any(), anyBoolean()))
        .thenReturn(
            new S3AllBuckets.Resolution(
                List.of("dokumente"), Map.of("logs-us", "Region us-east-1")));

    SourceConnectionTestResult result =
        connector.testConnection(request(Map.of("allBuckets", true)), null);

    assertThat(result.reachable()).isTrue();
    assertThat(result.message())
        .isEqualTo(
            "Anmeldung erfolgreich; der Zugang sieht 2 Buckets, die Bibliothek liest 1 davon."
                + " Nicht im Umfang: „logs-us“ (Region us-east-1).");
  }

  @Test
  void theConnectionTestFailsWhenTheLibraryCouldReadNoBucket() throws Exception {
    when(connectionService.resolveAllBuckets(anyString(), anyString(), any(), anyBoolean()))
        .thenReturn(
            new S3AllBuckets.Resolution(List.of(), Map.of("privat", S3AllBuckets.NOT_LISTABLE)));

    SourceConnectionTestResult result =
        connector.testConnection(request(Map.of("allBuckets", true)), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("keinen davon", "„privat“ (Auflisten nicht erlaubt");
  }

  @Test
  void theConnectionTestReadsEveryBucketWhenNoneIsLeftOut() throws Exception {
    when(connectionService.resolveAllBuckets(anyString(), anyString(), any(), anyBoolean()))
        .thenReturn(new S3AllBuckets.Resolution(List.of("a-docs", "b-docs"), Map.of()));

    SourceConnectionTestResult result =
        connector.testConnection(request(Map.of("allBuckets", true)), null);

    assertThat(result.message())
        .isEqualTo(
            "Anmeldung erfolgreich; der Zugang sieht 2 Buckets, die Bibliothek liest sie alle.");
  }

  @Test
  void theResolutionProbesEachBucketOfTheRegionAndLeavesOutTheRest() throws Exception {
    FakeS3ObjectStore store =
        new FakeS3ObjectStore()
            .bucketRegion("dokumente", "eu-central-1")
            .bucketRegion("logs-us", "us-east-1")
            .bucket("privat")
            .failBucket("privat", () -> new S3AccessException.ListForbidden("privat"))
            .bucket("umgezogen")
            .failBucket("umgezogen", () -> new S3AccessException.WrongRegionOrStyle("umgezogen"))
            .bucket("zz-spaet");
    S3BucketListing.Listed listed = (S3BucketListing.Listed) store.listBuckets();
    int[] probes = {0};

    S3AllBuckets.Resolution resolution =
        S3AllBuckets.resolve(store, listed, "EU-CENTRAL-1", Set.of(), () -> ++probes[0] > 3);

    assertThat(resolution.readable()).containsExactly("dokumente");
    assertThat(resolution.withheld()).isEmpty();
    assertThat(resolution.outside())
        .containsEntry("logs-us", "Region us-east-1")
        .containsEntry("privat", S3AllBuckets.NOT_LISTABLE)
        .containsEntry("umgezogen", S3AllBuckets.OTHER_ENDPOINT)
        .containsEntry("zz-spaet", S3AllBuckets.NOT_PROBED);
    assertThat(store.calls())
        .contains("probe dokumente", "probe privat", "probe umgezogen")
        .noneMatch(call -> call.contains("logs-us"));
  }

  @Test
  void anUnreadableBucketTheLibraryHoldsDocumentsFromIsWithheldNotLeftOut() throws Exception {
    FakeS3ObjectStore store =
        new FakeS3ObjectStore()
            .bucket("dokumente")
            .bucketRegion("logs-us", "us-east-1")
            .bucket("privat")
            .failBucket("privat", () -> new S3AccessException.BucketNotFound("privat"));
    S3BucketListing.Listed listed = (S3BucketListing.Listed) store.listBuckets();

    S3AllBuckets.Resolution resolution =
        S3AllBuckets.resolve(
            store, listed, "eu-central-1", Set.of("logs-us", "privat"), () -> false);

    assertThat(resolution.readable()).containsExactly("dokumente");
    assertThat(resolution.withheld())
        .containsEntry("logs-us", "Region us-east-1")
        .containsEntry("privat", S3AllBuckets.GONE);
    assertThat(resolution.outside()).isEmpty();
    assertThat(resolution.inScope()).containsExactly("dokumente", "logs-us", "privat");
  }

  @Test
  void aHeldBucketTheListingNoLongerNamesIsGoneOnlyWhenTheStoreConfirmsIt() throws Exception {
    FakeS3ObjectStore store =
        new FakeS3ObjectStore()
            .bucket("dokumente")
            .failBucket("ohne-code", () -> new S3AccessException.BucketNotFound("ohne-code"))
            .failBucket("gesperrt", () -> new S3AccessException.ListForbidden("gesperrt"))
            .failBucket("gedrosselt", () -> new S3AccessException.RateLimited(5));
    S3BucketListing.Listed listed = new S3BucketListing.Listed(List.of("dokumente"));

    S3AllBuckets.Resolution resolution =
        S3AllBuckets.resolve(
            store,
            listed,
            "eu-central-1",
            Set.of("dokumente", "geloescht", "ohne-code", "gesperrt", "gedrosselt"),
            () -> false);

    assertThat(resolution.readable()).containsExactly("dokumente");
    assertThat(resolution.withheld())
        .containsOnlyKeys("ohne-code", "gesperrt", "gedrosselt")
        .containsValue(S3AllBuckets.UNCONFIRMED);
    assertThat(resolution.inScope()).doesNotContain("geloescht");
    assertThat(resolution.seen()).isEqualTo(1);
    assertThat(store.calls())
        .contains("probe geloescht", "probe ohne-code", "probe gesperrt", "probe gedrosselt");
  }

  @Test
  void aHeldUnlistedBucketWhoseAddressIsRefusedStaysWithheldWithoutARequest() throws Exception {
    FakeS3ObjectStore store = new FakeS3ObjectStore().bucket("dokumente");

    S3AllBuckets.Resolution resolution =
        S3AllBuckets.resolve(
            store,
            new S3BucketListing.Listed(List.of("dokumente")),
            "eu-central-1",
            Set.of("geloescht"),
            bucket -> {
              throw new S3AccessException.Unreachable("unbekannter Host");
            },
            () -> false);

    assertThat(resolution.readable()).containsExactly("dokumente");
    assertThat(resolution.withheld()).containsEntry("geloescht", S3AllBuckets.UNCONFIRMED);
    assertThat(store.calls()).doesNotContain("probe geloescht");
  }

  @Test
  void aThrottledProbeIsTheCallersFailureNotALeftOutBucket() {
    FakeS3ObjectStore store =
        new FakeS3ObjectStore()
            .bucket("dokumente")
            .failBucket("dokumente", () -> new S3AccessException.RateLimited(5));

    assertThatThrownBy(
            () ->
                S3AllBuckets.resolve(
                    store,
                    new S3BucketListing.Listed(List.of("dokumente")),
                    "us-east-1",
                    Set.of("dokumente"),
                    () -> false))
        .isInstanceOf(S3AccessException.RateLimited.class);
  }

  @Test
  void withoutAllBucketsAScopeIsStillRequired() {
    assertThatThrownBy(() -> connector.readSettings(ConnectorData.of(Map.of("scopes", List.of()))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Mindestens ein Geltungsbereich");
  }
}
