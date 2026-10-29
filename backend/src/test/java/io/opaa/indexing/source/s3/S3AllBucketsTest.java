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
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import java.util.Map;
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
  void withoutAllBucketsAScopeIsStillRequired() {
    assertThatThrownBy(() -> connector.readSettings(ConnectorData.of(Map.of("scopes", List.of()))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Mindestens ein Geltungsbereich");
  }
}
