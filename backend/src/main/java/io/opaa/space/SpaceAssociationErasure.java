package io.opaa.space;

import io.opaa.knowledge.ErasedLibraryReferences;
import org.springframework.stereotype.Component;

/** Removes the space associations of an erased library before its row goes. */
@Component
class SpaceAssociationErasure implements ErasedLibraryReferences {

  private final SpaceAssetAssociationRepository associations;

  SpaceAssociationErasure(SpaceAssetAssociationRepository associations) {
    this.associations = associations;
  }

  @Override
  public String countKey() {
    return "spaceAssociationsRemoved";
  }

  @Override
  public int remove(ErasedLibrary erased) {
    return associations.deleteAllByAssetId(erased.libraryId());
  }

  @Override
  public long remaining(ErasedLibrary erased) {
    return associations.countByAssetId(erased.libraryId());
  }
}
