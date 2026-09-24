package io.opaa.group;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The tab "Gruppen ohne Wirkung" (#1819, ADR-0036 Entscheidung 6): internal groups that hold
 * nothing and reach nobody. It makes Wildwuchs visible without preventing it - deleting stays a
 * decision, and this list is where somebody makes it.
 */
@Component
class GroupsWithoutEffectSource implements SuccessionFindingSource {

  private final GroupEffectReader reader;

  GroupsWithoutEffectSource(GroupEffectReader reader) {
    this.reader = reader;
  }

  @Override
  public SuccessionKind kind() {
    return SuccessionKind.GROUP_WITHOUT_EFFECT;
  }

  @Override
  public boolean answersFor(SuccessionObjectType objectType) {
    return objectType == SuccessionObjectType.GROUP;
  }

  @Override
  public List<SuccessionFinding> findingsOf(UUID organizationId) {
    return reader.groupsWithoutEffect(organizationId);
  }
}
