package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * What a {@link SourceConnector} is, as the administration asks it instead of branching on the
 * source type.
 *
 * @param indexingRun whether a run fills the library - a library with a run can be scheduled, is
 *     created under the connector-library capability, carries a share cap and takes its whole
 *     bestand with it when deleted; a library without one is curated document by document through
 *     uploads
 * @param settingFields the connector-owned fields of the flat request
 * @param pushIntake the push intake the connector offers, {@code null} for none
 * @param fullSyncInterval the instance-wide rhythm of the connector's full reconciliation, which a
 *     library may lengthen; {@code null} for a connector whose every run is a full one
 */
public record SourceConnectorDescriptor(
    DocumentSourceType type,
    boolean indexingRun,
    Set<SourceSettingField> settingFields,
    PushIntake pushIntake,
    Duration fullSyncInterval) {

  public SourceConnectorDescriptor {
    Objects.requireNonNull(type, "type");
    settingFields = Set.copyOf(settingFields);
  }

  /** A connector with a run and neither own fields, push intake nor full-sync rhythm. */
  public static SourceConnectorDescriptor runBased(DocumentSourceType type) {
    return new SourceConnectorDescriptor(type, true, Set.of(), null, null);
  }

  /** Whether {@code field} belongs to this connector. */
  public boolean owns(SourceSettingField field) {
    return settingFields.contains(field);
  }
}
