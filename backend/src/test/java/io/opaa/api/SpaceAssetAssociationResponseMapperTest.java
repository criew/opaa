package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AssetSpaceAssociationListResponse;
import io.opaa.api.dto.AssetSpaceAssociationResponse;
import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.SpaceAssetAssociationListResponse;
import io.opaa.api.dto.SpaceAssetAssociationResponse;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.space.AssetSpaceLink;
import io.opaa.space.AssetSpaceLinks;
import io.opaa.space.SpaceAssetAssociation;
import io.opaa.space.SpaceAssetLink;
import io.opaa.space.SpaceAssetLinks;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities - #869 review: the
 * service tests recompute response-shaped values from the entity themselves, so they exercise that
 * recomputation, not {@link SpaceAssetAssociationResponseMapper}. These tests are what actually pin
 * the mapper's field-by-field behaviour.
 */
class SpaceAssetAssociationResponseMapperTest {

  private SpaceAssetAssociation association() {
    return new SpaceAssetAssociation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  private static SpaceAssetLink link(
      SpaceAssetAssociation association, boolean readable, String name, String createdBy) {
    return new SpaceAssetLink(
        association,
        KnowledgeLibrary.ASSET_TYPE,
        readable,
        name,
        readable ? "Beschreibung" : null,
        createdBy);
  }

  @Test
  void toResponseIncludesTheAssetNameWhenReadableByCaller() {
    SpaceAssetAssociation association = association();

    SpaceAssetAssociationResponse response =
        SpaceAssetAssociationResponseMapper.toResponse(
            link(association, true, "Bibliothek", "Ada Lovelace"));

    assertThat(response.getAssetType()).isEqualTo(AssetType.KNOWLEDGE_LIBRARY);
    assertThat(response.getAssetId()).isEqualTo(association.getAssetId());
    assertThat(response.getReadableByCaller()).isTrue();
    assertThat(response.getName()).isEqualTo("Bibliothek");
    assertThat(response.getDescription()).isEqualTo("Beschreibung");
    assertThat(response.getCreatedByUserId()).isEqualTo(association.getCreatedByUserId());
    assertThat(response.getCreatedByDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getCreatedAt()).isEqualTo(association.getCreatedAt());
  }

  @Test
  void toResponseOmitsTheAssetNameWhenNotReadableByCaller() {
    // #706 review: a CURATOR/ADMIN/owner sees an association they cannot themselves read, but
    // never its name - name and description must be absent whenever readableByCaller is false.
    SpaceAssetAssociationResponse response =
        SpaceAssetAssociationResponseMapper.toResponse(
            link(association(), false, null, "Ada Lovelace"));

    assertThat(response.getReadableByCaller()).isFalse();
    assertThat(response.getName()).isNull();
    assertThat(response.getDescription()).isNull();
  }

  @Test
  void toListResponseKeepsHasAssociationsIndependentOfAnEmptyItemsList() {
    // #706 review, finding 2: hasAssociations must stay true even when every item was filtered
    // out of the (possibly filtered) items list - the two are computed independently.
    SpaceAssetAssociationListResponse response =
        SpaceAssetAssociationResponseMapper.toListResponse(
            new SpaceAssetLinks(true, false, List.of()));

    assertThat(response.getHasAssociations()).isTrue();
    assertThat(response.getNarrowsSearch()).isFalse();
    assertThat(response.getItems()).isEmpty();
  }

  @Test
  void toListResponseMapsEveryItemInOrder() {
    SpaceAssetLinks links =
        new SpaceAssetLinks(
            true,
            true,
            List.of(link(association(), true, "A", null), link(association(), true, "B", null)));

    SpaceAssetAssociationListResponse response =
        SpaceAssetAssociationResponseMapper.toListResponse(links);

    assertThat(response.getItems())
        .extracting(SpaceAssetAssociationResponse::getName)
        .containsExactly("A", "B");
    assertThat(response.getNarrowsSearch()).isTrue();
  }

  @Test
  void toAssetSpaceResponseCarriesTheSpaceNameAndNarrowerReaderCircleFlag() {
    SpaceAssetAssociation association = association();
    AssetSpaceLink link =
        new AssetSpaceLink(association, "Fachbereich", true, "Ada Lovelace", true);

    AssetSpaceAssociationResponse response =
        SpaceAssetAssociationResponseMapper.toAssetSpaceResponse(link);

    assertThat(response.getSpaceId()).isEqualTo(association.getSpaceId());
    assertThat(response.getSpaceName()).isEqualTo("Fachbereich");
    assertThat(response.getCreatedByUserId()).isEqualTo(association.getCreatedByUserId());
    assertThat(response.getCreatedByDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getCreatedAt()).isEqualTo(association.getCreatedAt());
    assertThat(response.getNarrowerReaderCircle()).isTrue();
  }

  // #1939: the reduced entry a reader below MANAGER gets - space and name, nothing else. Asserted
  // field by field, because every omitted field is a deliberate non-disclosure.
  @Test
  void toAssetSpaceResponseOmitsEveryManagementFieldWithoutManagementDetail() {
    SpaceAssetAssociation association = association();
    AssetSpaceLink link =
        new AssetSpaceLink(association, "Fachbereich", false, "Ada Lovelace", false);

    AssetSpaceAssociationResponse response =
        SpaceAssetAssociationResponseMapper.toAssetSpaceResponse(link);

    assertThat(response.getSpaceId()).isEqualTo(association.getSpaceId());
    assertThat(response.getSpaceName()).isEqualTo("Fachbereich");
    assertThat(response.getCreatedByUserId()).isNull();
    assertThat(response.getCreatedByDisplayName()).isNull();
    assertThat(response.getCreatedAt()).isNull();
    assertThat(response.getNarrowerReaderCircle()).isNull();
  }

  @Test
  void toAssetSpaceListResponseReturnsAnEmptyListForNoLinksInsteadOfNull() {
    AssetSpaceAssociationListResponse response =
        SpaceAssetAssociationResponseMapper.toAssetSpaceListResponse(
            new AssetSpaceLinks(List.of(), 0));

    assertThat(response.getItems()).isEmpty();
    assertThat(response.getHiddenCount()).isZero();
  }

  // #1939: hiddenCount zählt die Spaces, die dieser Aufrufer nicht erfahren darf - er steht neben
  // den Einträgen, nicht in ihnen.
  @Test
  void toAssetSpaceListResponseCarriesTheHiddenCountAlongsideTheItems() {
    AssetSpaceAssociationListResponse response =
        SpaceAssetAssociationResponseMapper.toAssetSpaceListResponse(
            new AssetSpaceLinks(
                List.of(new AssetSpaceLink(association(), "Fachbereich", false, null, false)), 2));

    assertThat(response.getItems())
        .singleElement()
        .extracting(AssetSpaceAssociationResponse::getSpaceName)
        .isEqualTo("Fachbereich");
    assertThat(response.getHiddenCount()).isEqualTo(2);
  }
}
