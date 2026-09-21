package io.opaa.group;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The tab "Freigaben ohne Empfänger" (#1819, ADR-0036 Entscheidung 6) - the visible signal for the
 * unprotected token path: after a renamed group claim, the releases to the old group are dead, and
 * nothing else in the installation shows it.
 */
@Component
class GrantsWithoutRecipientSource implements SuccessionFindingSource {

  private final GroupEffectReader reader;

  GrantsWithoutRecipientSource(GroupEffectReader reader) {
    this.reader = reader;
  }

  @Override
  public SuccessionKind kind() {
    return SuccessionKind.GRANTS_WITHOUT_RECIPIENT;
  }

  @Override
  public SuccessionObjectType objectType() {
    return SuccessionObjectType.GROUP;
  }

  @Override
  public List<SuccessionFinding> findingsOf(UUID organizationId) {
    return reader.grantsWithoutRecipient(organizationId);
  }
}
