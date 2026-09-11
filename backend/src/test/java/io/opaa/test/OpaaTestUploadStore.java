package io.opaa.test;

import io.opaa.indexing.source.s3.MinioFixture;

/**
 * The one MinIO bucket the S3 upload store of {@link OpaaMockedChatModelIntegrationTest} writes to,
 * created once per JVM. Shared constant rather than a value the test class computes itself: the
 * initializer and the assertions have to name the same bucket, and a value computed per class would
 * key that class to its own Spring context.
 */
public final class OpaaTestUploadStore {

  public static final String BUCKET = MinioFixture.get().createBucket("opaa-upload-store");

  private OpaaTestUploadStore() {}
}
