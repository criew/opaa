package io.opaa.knowledge;

import java.util.Map;
import java.util.Optional;

/**
 * The outbound data flow of the compliance manifest each connector type opens, for the disclosure
 * of the data collection. A type missing here ({@code FILESYSTEM}, {@code UPLOAD}) reads only from
 * the server itself or from uploads.
 */
public final class ConnectorDataFlows {

  static final Map<String, String> BY_TYPE =
      Map.of(
          "HTTP_DIRECTORY", "source-web",
          "RSS_FEED", "source-rss",
          "CONFLUENCE", "source-confluence",
          "DROPBOX", "source-dropbox",
          "GOOGLE_DRIVE", "source-google-drive",
          "NEXTCLOUD", "source-nextcloud",
          "SHAREPOINT", "source-sharepoint",
          "ONEDRIVE", "source-onedrive",
          "S3", "source-s3",
          "SMB", "source-smb");

  private ConnectorDataFlows() {}

  /** The data flow of {@code type}; empty for a type that sends nothing out. */
  public static Optional<String> of(SourceType type) {
    return Optional.ofNullable(BY_TYPE.get(type.key()));
  }
}
