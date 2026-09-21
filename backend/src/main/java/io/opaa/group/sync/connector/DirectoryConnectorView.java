package io.opaa.group.sync.connector;

import io.opaa.api.types.DirectoryConnectorType;
import java.time.Instant;

/**
 * A provider's stored directory access as the management sees it (#1817). Deliberately carries no
 * secret field at all rather than a masked one: a value that is never read cannot leak, and "a
 * secret is stored" already follows from the presence of this record. {@code baseUrl} and {@code
 * realm} are the effective values - the override where one is stored, otherwise derived from the
 * provider's issuer URI. Domain counterpart of the generated {@code DirectoryConnectorResponse},
 * mapped by {@code io.opaa.api.DirectoryConnectorResponseMapper}.
 */
public record DirectoryConnectorView(
    DirectoryConnectorType type,
    String baseUrl,
    String realm,
    String clientId,
    Instant updatedAt) {}
