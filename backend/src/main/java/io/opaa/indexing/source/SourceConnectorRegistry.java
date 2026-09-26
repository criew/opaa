package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every {@link SourceConnector} by the source type it serves - the one way the administration
 * reaches a connector. Built from the connector beans; startup fails unless every {@link
 * DocumentSourceType} has exactly one connector whose {@link SourceConnectorDescriptor#indexingRun}
 * agrees with {@link DocumentSourceType#hasIndexingRun}, every {@link SourceSettingField} has one
 * owner, and no push intake or browse kind is offered twice.
 */
public class SourceConnectorRegistry {

  private final Map<DocumentSourceType, SourceConnector> connectors =
      new EnumMap<>(DocumentSourceType.class);
  private final Map<SourceSettingField, DocumentSourceType> fieldOwners =
      new EnumMap<>(SourceSettingField.class);
  private final Map<PushIntake, DocumentSourceType> pushIntakeOwners =
      new EnumMap<>(PushIntake.class);
  private final Map<SourceBrowser.Kind, SourceConnector> browsers =
      new EnumMap<>(SourceBrowser.Kind.class);

  public SourceConnectorRegistry(List<SourceConnector> connectorBeans) {
    for (SourceConnector connector : connectorBeans) {
      SourceConnectorDescriptor descriptor = connector.descriptor();
      DocumentSourceType type = descriptor.type();
      requireUnique(connectors.put(type, connector), "source type " + type);
      if (descriptor.indexingRun() != type.hasIndexingRun()) {
        throw new IllegalStateException(
            "SourceConnector for " + type + " disagrees with hasIndexingRun()");
      }
      for (SourceSettingField field : descriptor.settingFields()) {
        requireUnique(fieldOwners.put(field, type), "setting field " + field);
      }
      if (descriptor.pushIntake() != null) {
        requireUnique(
            pushIntakeOwners.put(descriptor.pushIntake(), type),
            "push intake " + descriptor.pushIntake());
      }
      if (connector instanceof SourceBrowser browser) {
        requireUnique(
            browsers.put(browser.browseKind(), connector), "browse kind " + browser.browseKind());
      }
    }
    Set<DocumentSourceType> missing = EnumSet.allOf(DocumentSourceType.class);
    missing.removeAll(connectors.keySet());
    if (!missing.isEmpty()) {
      throw new IllegalStateException("No SourceConnector bean is registered for " + missing);
    }
    Set<SourceSettingField> unowned = EnumSet.allOf(SourceSettingField.class);
    unowned.removeAll(fieldOwners.keySet());
    if (!unowned.isEmpty()) {
      throw new IllegalStateException("No SourceConnector owns the setting field(s) " + unowned);
    }
  }

  private static void requireUnique(Object previous, String what) {
    if (previous != null) {
      throw new IllegalStateException("More than one SourceConnector serves " + what);
    }
  }

  /** The connector serving {@code type}. Always succeeds after construction. */
  public SourceConnector connector(DocumentSourceType type) {
    return connectors.get(type);
  }

  public SourceConnectorDescriptor descriptor(DocumentSourceType type) {
    return connector(type).descriptor();
  }

  /**
   * Validates the configuration of a new library of {@code type} through its connector. Foreign
   * fields are refused in the order the request documents: edition and S3 settings before the
   * connector, every other one right after it.
   */
  public SourceSettings validateNew(DocumentSourceType type, SourceSettings requested) {
    rejectForeignSettings(
        type, requested, SourceSettingField.CONFLUENCE_EDITION, SourceSettingField.S3_SETTINGS);
    SourceSettings validated = connector(type).validate(requested);
    rejectForeignSettings(type, requested, SourceSettingField.values());
    return validated;
  }

  /**
   * Validates a change of {@code library}'s configuration through its connector. Foreign fields are
   * refused in the order the request documents: edition, spaces and S3 settings before the
   * connector, every other one right after it.
   */
  public SourceSettings validateChange(
      KnowledgeLibrary library, SourceSettings requested, boolean replacesConnection) {
    DocumentSourceType type = library.getSourceType();
    rejectForeignSettings(
        type,
        requested,
        SourceSettingField.CONFLUENCE_EDITION,
        SourceSettingField.CONFLUENCE_SPACES,
        SourceSettingField.S3_SETTINGS);
    SourceSettings validated =
        connector(type).validateChange(library, requested, replacesConnection);
    rejectForeignSettings(type, requested, SourceSettingField.values());
    return validated;
  }

  /**
   * Refuses the first of {@code fields}, in the given order, that {@code settings} carries although
   * {@code type} does not own it.
   */
  public void rejectForeignSettings(
      DocumentSourceType type, SourceSettings settings, SourceSettingField... fields) {
    SourceConnectorDescriptor descriptor = descriptor(type);
    for (SourceSettingField field : fields) {
      if (field.isSetIn(settings) && !descriptor.owns(field)) {
        throw new ValidationException(field.foreignMessage(fieldOwners.get(field)));
      }
    }
  }

  /**
   * The source type whose libraries carry {@code intake}.
   *
   * @throws IllegalStateException when no connector offers it
   */
  public DocumentSourceType ownerOf(PushIntake intake) {
    DocumentSourceType owner = pushIntakeOwners.get(intake);
    if (owner == null) {
      throw new IllegalStateException("No SourceConnector offers push intake " + intake);
    }
    return owner;
  }

  /**
   * The connector offering {@code kind} - a {@link SourceBrowser} by construction.
   *
   * @throws IllegalStateException when no connector offers it
   */
  public SourceConnector browser(SourceBrowser.Kind kind) {
    SourceConnector connector = browsers.get(kind);
    if (connector == null) {
      throw new IllegalStateException("No SourceConnector offers browse kind " + kind);
    }
    return connector;
  }
}
