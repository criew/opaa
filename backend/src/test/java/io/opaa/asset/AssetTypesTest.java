package io.opaa.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.common.NotFoundException;
import io.opaa.permission.AssetType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An inactive asset type (ADR-0044) stays registered for the paths that handle existing assets,
 * but is no longer offered to readers, and a request path naming it answers like an unknown asset.
 */
class AssetTypesTest {

  private static final AssetType SHOWN = AssetType.of("SHOWN");
  private static final AssetType HIDDEN = AssetType.of("HIDDEN");

  private final AssetTypes assetTypes =
      new AssetTypes(List.of(new Definition(SHOWN, true), new Definition(HIDDEN, false)));

  @Test
  void anInactiveTypeStaysRegisteredButIsNotActive() {
    assertThat(assetTypes.registered()).containsExactly(SHOWN, HIDDEN);
    assertThat(assetTypes.active()).containsExactly(SHOWN);
    assertThat(assetTypes.isActive(SHOWN)).isTrue();
    assertThat(assetTypes.isActive(HIDDEN)).isFalse();
    assertThat(assetTypes.isActive(AssetType.of("UNKNOWN"))).isFalse();
  }

  @Test
  void theOwnerAndHistoryPathsStillResolveAnInactiveType() {
    assertThat(assetTypes.find(HIDDEN)).isPresent();
    assertThat(assetTypes.require(HIDDEN).singular()).isEqualTo("HIDDEN");
    assertThat(assetTypes.require("HIDDEN").assetType()).isEqualTo(HIDDEN);
  }

  @Test
  void aRequestPathNamingAnInactiveTypeAnswersLikeAnUnknownAsset() {
    assertThat(assetTypes.requireActive("SHOWN").assetType()).isEqualTo(SHOWN);
    assertThatThrownBy(() -> assetTypes.requireActive("HIDDEN"))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Objekt nicht gefunden");
    assertThatThrownBy(() -> assetTypes.requireActive("UNKNOWN"))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Objekt nicht gefunden");
    assertThatThrownBy(() -> assetTypes.requireActive("not a type"))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Objekt nicht gefunden");
  }

  /** A declaration with nothing but its key and its state. */
  record Definition(AssetType assetType, boolean enabled) implements AssetTypeDefinition {

    @Override
    public AuditObjectType auditObjectType() {
      return AuditObjectType.KNOWLEDGE_LIBRARY;
    }

    @Override
    public AuditEventType createdAuditEventType() {
      return AuditEventType.LIBRARY_CREATED;
    }

    @Override
    public String singular() {
      return assetType.value();
    }

    @Override
    public String plural() {
      return assetType.value();
    }
  }
}
