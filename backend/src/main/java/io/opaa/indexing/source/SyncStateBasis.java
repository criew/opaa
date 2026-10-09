package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fingerprint of the settings a library's {@link SourceSyncState} means something under:
 * address, path and the connector settings its connector compares ({@link
 * SourceConnector#settingsState}). A state is continued only under the fingerprint it was written
 * with; equal settings give an equal fingerprint whatever the order of their keys.
 */
public final class SyncStateBasis {

  /** Every connector setting counts - for a frame that knows no connector. */
  public static final SyncStateBasis WHOLE_SETTINGS = new SyncStateBasis(null);

  private static final String VERSION = "1";

  private static final JsonMapper CANONICAL =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  /** Looked up per call: the connectors reach the run frame that asks. */
  private final Supplier<SourceConnectorRegistry> connectors;

  /** The basis as {@code connectors} compare the settings of their libraries. */
  public SyncStateBasis(Supplier<SourceConnectorRegistry> connectors) {
    this.connectors = connectors;
  }

  /** The fingerprint of {@code library} under {@code settings}, its effective configuration. */
  public String of(KnowledgeLibrary library, SourceSettings settings) {
    ConnectorData connectorSettings = settings.connectorSettings();
    Map<String, ?> compared =
        connectors == null
            ? (connectorSettings == null ? Map.of() : connectorSettings.asMap())
            : connectors
                .get()
                .connector(library.getSourceType())
                .settingsState(library, connectorSettings);
    Map<String, Object> basis = new LinkedHashMap<>();
    basis.put("v", VERSION);
    basis.put("sourceUrl", settings.sourceUrl());
    basis.put("sourcePath", settings.sourcePath());
    basis.put("settings", compared);
    return sha256(CANONICAL.writeValueAsString(basis));
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
