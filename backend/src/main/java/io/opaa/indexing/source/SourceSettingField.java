package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;

/**
 * The connector-owned fields of the flat library request. Exactly one connector owns each ({@link
 * SourceConnectorDescriptor#settingFields}); a library of any other type that carries one is
 * refused with {@link #foreignMessage}, naming the owner the {@link SourceConnectorRegistry}
 * resolves.
 */
public enum SourceSettingField {
  CONFLUENCE_EDITION("confluenceEdition ist"),
  S3_SETTINGS("s3Settings sind"),
  CONFLUENCE_SPACES("confluenceSpaces sind"),
  CONFLUENCE_FULL_SYNC_INTERVAL_DAYS("confluenceFullSyncIntervalDays ist");

  private final String subject;

  SourceSettingField(String subject) {
    this.subject = subject;
  }

  /** Whether {@code settings} carries a value for this field. */
  public boolean isSetIn(SourceSettings settings) {
    return switch (this) {
      case CONFLUENCE_EDITION -> settings.confluenceEdition() != null;
      case S3_SETTINGS -> settings.s3Settings() != null;
      case CONFLUENCE_SPACES -> settings.confluenceSpaces() != null;
      case CONFLUENCE_FULL_SYNC_INTERVAL_DAYS -> settings.confluenceFullSyncIntervalDays() != null;
    };
  }

  /** The German 400 message for this field on a library that is not of type {@code owner}. */
  public String foreignMessage(DocumentSourceType owner) {
    return subject + " nur für sourceType " + owner + " zulässig";
  }
}
