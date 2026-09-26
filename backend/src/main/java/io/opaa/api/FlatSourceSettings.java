package io.opaa.api;

import io.opaa.api.dto.ConfluenceSpaceRef;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.S3ScopeCheck;
import io.opaa.api.dto.S3ScopeRef;
import io.opaa.api.dto.S3Settings;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.library.ConnectorSettingsRequest;
import io.opaa.library.LibraryDetail;
import io.opaa.library.LibraryManagementDetail;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connector-owned fields of the flat library API - {@code confluenceEdition}, {@code
 * confluenceSpaces}, {@code confluenceFullSyncIntervalDays} and {@code s3Settings} - translated
 * into the connector settings of ADR-0038 and back. A field on a library of another type is refused
 * naming its owner: edition and S3 settings (and on a change the spaces) before the connector
 * validates, every other one right after it.
 */
public final class FlatSourceSettings implements ConnectorSettingsRequest {

  private final ConfluenceEdition confluenceEdition;
  private final List<Map<String, Object>> confluenceSpaces;
  private final Integer confluenceFullSyncIntervalDays;
  private final ConnectorData s3Settings;

  /**
   * @param confluenceSpaces the selection as {@link #space} entries, {@code null} for none
   * @param s3Settings the S3 settings as the S3 connector read them, {@code null} for none
   */
  public FlatSourceSettings(
      ConfluenceEdition confluenceEdition,
      List<Map<String, Object>> confluenceSpaces,
      Integer confluenceFullSyncIntervalDays,
      ConnectorData s3Settings) {
    this.confluenceEdition = confluenceEdition;
    this.confluenceSpaces = confluenceSpaces;
    this.confluenceFullSyncIntervalDays = confluenceFullSyncIntervalDays;
    this.s3Settings = s3Settings;
  }

  /** One entry of {@code confluenceSpaces}. */
  public static Map<String, Object> space(String key, String name) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("key", key);
    entry.put("name", name);
    return entry;
  }

  /**
   * The request's fields, the S3 settings read by the S3 connector - a malformed selection or S3
   * value is the caller's 400 before anything else is looked at, as a {@code null} element is.
   */
  static FlatSourceSettings of(
      ConfluenceEdition edition,
      List<ConfluenceSpaceRef> spaces,
      Integer fullSyncIntervalDays,
      S3Settings s3,
      SourceConnectorRegistry connectors) {
    List<Map<String, Object>> selection = null;
    if (spaces != null) {
      selection = new ArrayList<>();
      for (ConfluenceSpaceRef ref : spaces) {
        if (ref == null) {
          throw new ValidationException(
              "confluenceSpaces: jeder Eintrag braucht einen Space-Schlüssel");
        }
        selection.add(space(ref.getKey(), ref.getName()));
      }
    }
    return new FlatSourceSettings(
        edition, selection, fullSyncIntervalDays, readS3Settings(s3, connectors));
  }

  /** {@code null} stays {@code null}; otherwise the S3 connector's reading of the value. */
  static ConnectorData readS3Settings(S3Settings settings, SourceConnectorRegistry connectors) {
    if (settings == null) {
      return null;
    }
    List<Map<String, Object>> scopes = new ArrayList<>();
    if (settings.getScopes() != null) {
      for (S3ScopeRef ref : settings.getScopes()) {
        if (ref == null) {
          throw new ValidationException("s3Settings: jeder Geltungsbereich braucht einen Bucket");
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("bucket", ref.getBucket());
        scope.put("prefix", ref.getPrefix());
        scopes.add(scope);
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("region", settings.getRegion());
    data.put("pathStyle", Boolean.TRUE.equals(settings.getPathStyle()));
    data.put("scopes", scopes);
    data.put("includePatterns", settings.getIncludePatterns());
    data.put("excludePatterns", settings.getExcludePatterns());
    return connectors.connector(DocumentSourceType.S3).readSettings(ConnectorData.of(data));
  }

  @Override
  public ConnectorData addressedTo(DocumentSourceType type, boolean change) {
    refuse(type, Field.EDITION);
    if (change) {
      refuse(type, Field.SPACES);
    }
    refuse(type, Field.S3);
    if (type == DocumentSourceType.S3) {
      return s3Settings;
    }
    if (type != DocumentSourceType.CONFLUENCE
        || (confluenceEdition == null
            && confluenceSpaces == null
            && confluenceFullSyncIntervalDays == null)) {
      return null;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    if (confluenceEdition != null) {
      data.put("edition", confluenceEdition.name());
    }
    if (confluenceSpaces != null) {
      data.put("spaces", confluenceSpaces);
    }
    if (confluenceFullSyncIntervalDays != null) {
      data.put("fullSyncIntervalDays", confluenceFullSyncIntervalDays);
    }
    return ConnectorData.of(data);
  }

  @Override
  public void rejectForeign(DocumentSourceType type, boolean change) {
    for (Field field : Field.values()) {
      refuse(type, field);
    }
  }

  private void refuse(DocumentSourceType type, Field field) {
    boolean set =
        switch (field) {
          case EDITION -> confluenceEdition != null;
          case S3 -> s3Settings != null;
          case SPACES -> confluenceSpaces != null;
          case INTERVAL -> confluenceFullSyncIntervalDays != null;
        };
    if (set && type != field.owner) {
      throw new ValidationException(
          field.subject + " nur für sourceType " + field.owner + " zulässig");
    }
  }

  /** The flat fields in the order a remaining foreign one is refused. */
  private enum Field {
    EDITION("confluenceEdition ist", DocumentSourceType.CONFLUENCE),
    S3("s3Settings sind", DocumentSourceType.S3),
    SPACES("confluenceSpaces sind", DocumentSourceType.CONFLUENCE),
    INTERVAL("confluenceFullSyncIntervalDays ist", DocumentSourceType.CONFLUENCE);

    private final String subject;
    private final DocumentSourceType owner;

    Field(String subject, DocumentSourceType owner) {
      this.subject = subject;
      this.owner = owner;
    }
  }

  /**
   * Writes the connector settings and the push-secret flag of {@code detail} onto the flat response
   * fields of its type: the edition, selection and S3 settings for every reader, the rhythm and the
   * secret flag at the management bar.
   */
  static void writeTo(LibraryResponse response, LibraryDetail detail) {
    DocumentSourceType type = detail.library().getSourceType();
    ConnectorData view = detail.connectorSettings();
    LibraryManagementDetail management = detail.managementDetail();
    if (type == DocumentSourceType.CONFLUENCE) {
      if (view != null) {
        response
            .confluenceEdition(ConfluenceEdition.valueOf(view.get("edition").toString()))
            .confluenceSpaces(toSpaceRefs(view.get("spaces")));
      }
      ConnectorData managed = management.connectorSettings();
      response
          .confluenceWebhookSecretSet(management.pushSecretSet())
          .confluenceFullSyncIntervalDays(
              managed == null ? null : integer(managed.get("fullSyncIntervalDays")));
    }
    if (type == DocumentSourceType.S3) {
      if (view != null) {
        response.s3Settings(toS3Settings(view));
      }
      response.s3EventsTokenSet(management.pushSecretSet());
    }
    response.confluenceFullSyncIntervalDefaultDays(management.fullSyncIntervalDefaultDays());
  }

  static S3Settings toS3Settings(ConnectorData settings) {
    List<S3ScopeRef> scopes = new ArrayList<>();
    for (Object item : list(settings.get("scopes"))) {
      Map<?, ?> scope = (Map<?, ?>) item;
      scopes.add(new S3ScopeRef((String) scope.get("bucket")).prefix((String) scope.get("prefix")));
    }
    return new S3Settings(scopes)
        .region((String) settings.get("region"))
        .pathStyle(Boolean.TRUE.equals(settings.get("pathStyle")))
        .includePatterns(strings(settings.get("includePatterns")))
        .excludePatterns(strings(settings.get("excludePatterns")));
  }

  /** The test's findings under the flat names: the detected edition and the S3 scope checks. */
  static ConfluenceEdition detectedEdition(ConnectorData details) {
    return details == null || details.get("edition") == null
        ? null
        : ConfluenceEdition.valueOf(details.get("edition").toString());
  }

  static List<S3ScopeCheck> scopeChecks(ConnectorData details) {
    if (details == null || details.get("scopes") == null) {
      return null;
    }
    List<S3ScopeCheck> checks = new ArrayList<>();
    for (Object item : list(details.get("scopes"))) {
      Map<?, ?> check = (Map<?, ?>) item;
      checks.add(
          new S3ScopeCheck(
                  (String) check.get("bucket"),
                  (String) check.get("prefix"),
                  Boolean.TRUE.equals(check.get("bucketReachable")),
                  Boolean.TRUE.equals(check.get("listAllowed")),
                  ((Number) check.get("objectCount")).longValue(),
                  Boolean.TRUE.equals(check.get("objectCountIsLowerBound")))
              .readAllowed((Boolean) check.get("readAllowed"))
              .message((String) check.get("message")));
    }
    return checks;
  }

  private static List<ConfluenceSpaceRef> toSpaceRefs(Object spaces) {
    List<ConfluenceSpaceRef> refs = new ArrayList<>();
    for (Object item : list(spaces)) {
      Map<?, ?> space = (Map<?, ?>) item;
      refs.add(new ConfluenceSpaceRef((String) space.get("key")).name((String) space.get("name")));
    }
    return refs;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static List<String> strings(Object value) {
    return list(value).stream().map(String::valueOf).toList();
  }

  private static Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }
}
