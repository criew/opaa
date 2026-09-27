package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How {@link S3SourceConnector#browse} turns the bucket listing into a {@link SourceListing}. */
class S3SourceConnectorBrowseTest {

  private final S3ConnectionService connectionService = mock(S3ConnectionService.class);
  private final S3SourceConnector connector =
      new S3SourceConnector(
          connectionService,
          new S3ClientFactory(S3Properties.defaults(), TargetAddressValidator.disabled()),
          mock(SourceSyncStateRepository.class),
          mock(S3OriginalAccess.class),
          mock(io.opaa.indexing.source.s3.events.S3EventService.class));

  private static SourceBrowser.Query query(String credentials) {
    return new SourceBrowser.Query(
        new SourceSettings(null, "https://s3.example.org", null, credentials, false, null), null);
  }

  @Test
  void aVisibleBucketListIsCompleteWithOneEntryPerBucket() throws Exception {
    when(connectionService.listBuckets(
            anyString(), any(), anyString(), anyBoolean(), any(), anyBoolean()))
        .thenReturn(new S3BucketListResult(true, List.of("dokumente", "archiv"), null));

    SourceListing listing = connector.browse(query("ak:sk"));

    assertThat(listing.complete()).isTrue();
    assertThat(listing.message()).isNull();
    assertThat(listing.entries())
        .extracting(SourceListing.Entry::key, SourceListing.Entry::name)
        .containsExactly(tuple("dokumente", null), tuple("archiv", null));
  }

  @Test
  void aKeyWithoutListAllMyBucketsYieldsAnIncompleteListingWithTheHint() throws Exception {
    when(connectionService.listBuckets(
            anyString(), any(), anyString(), anyBoolean(), any(), anyBoolean()))
        .thenReturn(new S3BucketListResult(false, List.of(), "Bucket-Name von Hand eintragen"));

    SourceListing listing = connector.browse(query("ak:sk"));

    assertThat(listing.complete()).isFalse();
    assertThat(listing.entries()).isEmpty();
    assertThat(listing.message()).isEqualTo("Bucket-Name von Hand eintragen");
  }

  @Test
  void aListingWithoutCredentialsIsRefused() {
    assertThatThrownBy(() -> connector.browse(query(" ")))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceCredentials sind für die Bucket-Auflistung erforderlich");
  }
}
