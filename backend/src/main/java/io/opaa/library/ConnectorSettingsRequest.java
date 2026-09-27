package io.opaa.library;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.ConnectorData;

/**
 * The connector settings a create or update request carries, before it is known which connector
 * reads them - resolved against the library's type once the caller has passed the permission check.
 * A request may address parts to another type than the library's; those are refused with a German
 * 400, in two steps around the connector's own validation.
 */
public interface ConnectorSettingsRequest {

  /** No connector settings at all. */
  ConnectorSettingsRequest NONE = of(null);

  /**
   * The settings addressed to a library of {@code type}, {@code null} for none. Refuses first what
   * the request addresses to another type and must not reach the connector's validation.
   *
   * @param change whether the request changes an existing library rather than creating one
   */
  ConnectorData addressedTo(DocumentSourceType type, boolean change);

  /** Refuses whatever part of the request is addressed to another type than {@code type}. */
  void rejectForeign(DocumentSourceType type, boolean change);

  /** Exactly {@code settings}, whatever the library's type. */
  static ConnectorSettingsRequest of(ConnectorData settings) {
    return new ConnectorSettingsRequest() {
      @Override
      public ConnectorData addressedTo(DocumentSourceType type, boolean change) {
        return settings;
      }

      @Override
      public void rejectForeign(DocumentSourceType type, boolean change) {}
    };
  }
}
