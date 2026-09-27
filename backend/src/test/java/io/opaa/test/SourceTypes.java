package io.opaa.test;

import io.opaa.knowledge.SourceType;

/**
 * The type keys of the delivered connectors, for tests that set up rows of a given type without
 * going through the connector itself. Production code names these keys only inside the connectors.
 */
public final class SourceTypes {

  public static final SourceType UPLOAD = SourceType.UPLOAD;
  public static final SourceType FILESYSTEM = SourceType.of("FILESYSTEM");
  public static final SourceType HTTP_DIRECTORY = SourceType.of("HTTP_DIRECTORY");
  public static final SourceType RSS_FEED = SourceType.of("RSS_FEED");
  public static final SourceType CONFLUENCE = SourceType.of("CONFLUENCE");
  public static final SourceType S3 = SourceType.of("S3");

  private SourceTypes() {}
}
