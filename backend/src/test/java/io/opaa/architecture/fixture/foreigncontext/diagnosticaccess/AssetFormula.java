package io.opaa.architecture.fixture.foreigncontext.diagnosticaccess;

import io.opaa.architecture.fixture.foreigncontext.permission.AssetAccessService;
import java.util.Set;
import java.util.UUID;

/** Reaches the target person's own formula through the asset shell, in all three shapes. */
public class AssetFormula {

  void candidates(AssetAccessService access, UUID target, UUID organization) {
    access.readableAssetIds("KNOWLEDGE_LIBRARY", target, organization);
    access.readableAssets("KNOWLEDGE_LIBRARY", target, organization);
    access.effectiveRoles("KNOWLEDGE_LIBRARY", Set.of(), target);
  }
}
