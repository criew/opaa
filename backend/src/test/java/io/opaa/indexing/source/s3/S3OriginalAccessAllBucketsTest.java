package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** "Original öffnen" under "Alle Buckets": any bucket the key reads, opened as a whole bucket. */
class S3OriginalAccessAllBucketsTest {

  private final S3ClientFactory clientFactory = mock(S3ClientFactory.class);
  private final S3OriginalAccess access =
      new S3OriginalAccess(clientFactory, S3Properties.defaults());
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Ablage",
          null,
          UUID.randomUUID(),
          SourceTypes.S3,
          null,
          "https://minio.intern.example:9000",
          null);
  private final SourceSettings source =
      new SourceSettings(
          null,
          "https://minio.intern.example:9000",
          "ak:sk",
          ConnectorData.of(Map.of("pathStyle", true, "allBuckets", true, "scopes", List.of())));

  @Test
  @SuppressWarnings("unchecked")
  void opensAnObjectOfAnyBucketAsTheWholeBucket() throws Exception {
    FakeS3ObjectStore store =
        new FakeS3ObjectStore().put("neu", "akte.txt", "inhalt", "text/plain");
    when(clientFactory.createForProbe(any(), anyCollection())).thenReturn(store);

    Optional<S3Download> download = access.download(library, source, "s3://neu/akte.txt");

    assertThat(download).isPresent();
    try {
      assertThat(Files.readString(download.get().file())).isEqualTo("inhalt");
    } finally {
      Files.deleteIfExists(download.get().file());
    }
    ArgumentCaptor<Collection<S3Scope>> scopes = ArgumentCaptor.forClass(Collection.class);
    verify(clientFactory).createForProbe(any(), scopes.capture());
    assertThat(scopes.getValue()).containsExactly(S3Scope.of("neu", ""));
  }

  @Test
  void aPathWhoseBucketCannotBeOneIsNoOriginal() throws Exception {
    assertThat(access.download(library, source, "s3://Kein_Bucket/akte.txt")).isEmpty();

    verify(clientFactory, never()).createForProbe(any(), anyCollection());
  }
}
