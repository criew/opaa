package io.opaa.permission.web;

import io.opaa.api.dto.AccessPathGroup;
import io.opaa.api.dto.AccessPathResponse;
import io.opaa.permission.AccessPath;
import io.opaa.permission.GroupAttribution;
import java.util.List;

/**
 * The ways of a Herleitung, for every web package that shows one (asset, space). Pure: whether a
 * way is withheld is decided in the domain service, not here.
 */
public final class AccessPathResponseMapper {

  private AccessPathResponseMapper() {}

  public static List<AccessPathResponse> toResponses(List<AccessPath> paths) {
    return paths.stream().map(AccessPathResponseMapper::toPath).toList();
  }

  private static AccessPathResponse toPath(AccessPath path) {
    return new AccessPathResponse(path.basis())
        .assetRole(path.assetRole())
        .spaceRole(path.spaceRole())
        .since(path.since())
        .group(toGroup(path.group()));
  }

  private static AccessPathGroup toGroup(GroupAttribution group) {
    return group == null
        ? null
        : new AccessPathGroup(group.id(), group.name(), group.origin(), group.mechanism())
            .providerName(group.providerName());
  }
}
