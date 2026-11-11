package io.opaa.asset;

import java.util.UUID;

/**
 * Who answers for an agent or a document template: a group by id or a function as free text - an
 * organizational unit or role such as "Referat Z3 - Organisation". Exactly one of the two is set
 * once {@link AssetResponsibilityService#validate} admitted it.
 */
public record ResponsibleUnit(UUID groupId, String function) {

  public static ResponsibleUnit ofGroup(UUID groupId) {
    return new ResponsibleUnit(groupId, null);
  }

  public static ResponsibleUnit ofFunction(String function) {
    return new ResponsibleUnit(null, function);
  }
}
