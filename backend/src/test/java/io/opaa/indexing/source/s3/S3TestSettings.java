package io.opaa.indexing.source.s3;

import io.opaa.knowledge.KnowledgeLibrary;

/** Writes S3 connector settings straight onto a library, the way the connector stores them. */
public final class S3TestSettings {

  private S3TestSettings() {}

  public static void configure(KnowledgeLibrary library, S3SourceSettings settings) {
    library.updateSourceSettings(S3SourceSettingsJson.write(settings));
  }
}
