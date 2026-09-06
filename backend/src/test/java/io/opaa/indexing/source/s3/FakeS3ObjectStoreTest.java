package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** The fake honours the port contract the later tickets rely on: paging, failures, ceilings. */
class FakeS3ObjectStoreTest {

  @Test
  void pagesInKeyOrderAndScriptsFailures() throws Exception {
    FakeS3ObjectStore fake =
        new FakeS3ObjectStore()
            .pageSize(2)
            .put("docs", "b.pdf", "B", "application/pdf")
            .put("docs", "a.pdf", "A", "application/pdf")
            .put("docs", "c/d.pdf", "D", "application/pdf")
            .bucket("leer");

    S3ListPage first = fake.listObjects(S3Scope.of("docs", ""), null);
    assertThat(first.objects()).extracting(S3ObjectSummary::key).containsExactly("a.pdf", "b.pdf");
    assertThat(first.isLast()).isFalse();
    S3ListPage second = fake.listObjects(S3Scope.of("docs", ""), first.nextContinuationToken());
    assertThat(second.objects()).extracting(S3ObjectSummary::key).containsExactly("c/d.pdf");
    assertThat(second.isLast()).isTrue();
    assertThat(fake.listObjects(S3Scope.of("docs", "c"), null).objects()).hasSize(1);
    assertThat(fake.listObjects(S3Scope.of("leer", ""), null).objects()).isEmpty();

    S3Download download = fake.getObject("docs", "a.pdf", 10);
    assertThat(Files.readString(download.file())).isEqualTo("A");
    Files.delete(download.file());
    assertThatThrownBy(() -> fake.getObject("docs", "a.pdf", 0))
        .isInstanceOf(S3AccessException.ObjectTooLarge.class);

    fake.failBucket("geheim", () -> new S3AccessException.ListForbidden("geheim"));
    assertThatThrownBy(() -> fake.listObjects(S3Scope.of("geheim", ""), null))
        .isInstanceOf(S3AccessException.ListForbidden.class);
    fake.failRead("docs", "b.pdf", () -> new S3AccessException.ReadForbidden("docs", "b.pdf"));
    assertThatThrownBy(() -> fake.headObject("docs", "b.pdf"))
        .isInstanceOf(S3AccessException.ReadForbidden.class);
    assertThatThrownBy(() -> fake.headObject("docs", "fehlt.pdf"))
        .isInstanceOf(S3AccessException.ObjectNotFound.class);
    fake.failNextCall(() -> new S3AccessException.RateLimited(5));
    assertThatThrownBy(() -> fake.headObject("docs", "a.pdf"))
        .isInstanceOf(S3AccessException.RateLimited.class);
    assertThat(fake.headObject("docs", "a.pdf").contentType()).isEqualTo("application/pdf");

    assertThat(fake.testAccess(S3Scope.of("docs", "")).readAllowed()).isTrue();
    assertThat(fake.testAccess(S3Scope.of("gibtsnicht", "")).bucketReachable()).isFalse();
    assertThat(fake.bucketListingPermitted(false).listBuckets())
        .isInstanceOf(S3BucketListing.NotPermitted.class);
    assertThat(fake.calls()).isNotEmpty();
    assertThat(fake.meter().requests()).isEqualTo(fake.calls().size());
  }
}
