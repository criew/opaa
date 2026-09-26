package io.opaa.indexing.source;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.knowledge.ConfluenceSpaceSelection;
import io.opaa.knowledge.sourcesettings.S3SourceSettings;
import java.util.List;

/**
 * A library's source configuration as a {@link SourceConnector} sees it: the connection fields
 * every run-based type draws from (path, address, proxy, credentials, TLS switch) and the
 * connector-owned fields of the flat library request ({@link SourceSettingField}). A
 * connector-owned field is {@code null} when absent - on a change that means "leave it as stored".
 */
public record SourceSettings(
    String sourcePath,
    String sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    boolean sourceInsecureSsl,
    ConfluenceEdition confluenceEdition,
    List<ConfluenceSpaceSelection> confluenceSpaces,
    Integer confluenceFullSyncIntervalDays,
    S3SourceSettings s3Settings) {

  /** Names whether credentials are set, never their value - the record may reach a log. */
  @Override
  public String toString() {
    return "SourceSettings[sourcePath="
        + sourcePath
        + ", sourceUrl="
        + sourceUrl
        + ", sourceProxy="
        + sourceProxy
        + ", sourceCredentials="
        + (sourceCredentials == null ? "null" : "***")
        + ", sourceInsecureSsl="
        + sourceInsecureSsl
        + ", confluenceEdition="
        + confluenceEdition
        + ", confluenceSpaces="
        + confluenceSpaces
        + ", confluenceFullSyncIntervalDays="
        + confluenceFullSyncIntervalDays
        + ", s3Settings="
        + s3Settings
        + "]";
  }

  /** A copy carrying {@code url} as its address - the normalised form a connector stores. */
  public SourceSettings withSourceUrl(String url) {
    return new SourceSettings(
        sourcePath,
        url,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        confluenceEdition,
        confluenceSpaces,
        confluenceFullSyncIntervalDays,
        s3Settings);
  }

  /** A copy carrying {@code spaces} and {@code intervalDays} as the Confluence-owned values. */
  public SourceSettings withConfluenceSelection(
      List<ConfluenceSpaceSelection> spaces, Integer intervalDays) {
    return new SourceSettings(
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        confluenceEdition,
        spaces,
        intervalDays,
        s3Settings);
  }
}
