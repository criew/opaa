package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibrarySpaceAssociationResponse;
import io.opaa.api.dto.SpaceLibraryAssociationListResponse;
import io.opaa.api.dto.SpaceLibraryAssociationResponse;
import io.opaa.space.LibrarySpaceLink;
import io.opaa.space.SpaceAssetAssociation;
import io.opaa.space.SpaceLibraryLink;
import io.opaa.space.SpaceLibraryLinks;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities - #869 review: the
 * service tests now recompute response-shaped values from the entity themselves, so they exercise
 * that recomputation, not {@link SpaceLibraryAssociationResponseMapper}. These tests are what
 * actually pin the mapper's field-by-field behaviour.
 */
class SpaceLibraryAssociationResponseMapperTest {

  private SpaceAssetAssociation association() {
    return new SpaceAssetAssociation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  @Test
  void toResponseIncludesTheLibraryNameWhenReadableByCaller() {
    SpaceAssetAssociation association = association();
    SpaceLibraryLink link = new SpaceLibraryLink(association, true, "Bibliothek", "Ada Lovelace");

    SpaceLibraryAssociationResponse response =
        SpaceLibraryAssociationResponseMapper.toResponse(link);

    assertThat(response.getLibraryId()).isEqualTo(association.getLibraryId());
    assertThat(response.getReadableByCaller()).isTrue();
    assertThat(response.getLibraryName()).isEqualTo("Bibliothek");
    assertThat(response.getCreatedByUserId()).isEqualTo(association.getCreatedByUserId());
    assertThat(response.getCreatedByDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getCreatedAt()).isEqualTo(association.getCreatedAt());
  }

  @Test
  void toResponseOmitsTheLibraryNameWhenNotReadableByCaller() {
    // #706 review: a CURATOR/ADMIN/owner sees an association they cannot themselves read, but
    // never its name - libraryName must be absent whenever readableByCaller is false.
    SpaceAssetAssociation association = association();
    SpaceLibraryLink link = new SpaceLibraryLink(association, false, null, "Ada Lovelace");

    SpaceLibraryAssociationResponse response =
        SpaceLibraryAssociationResponseMapper.toResponse(link);

    assertThat(response.getReadableByCaller()).isFalse();
    assertThat(response.getLibraryName()).isNull();
  }

  @Test
  void toListResponseKeepsHasAssociationsIndependentOfAnEmptyItemsList() {
    // #706 review, finding 2: hasAssociations must stay true even when every item was filtered
    // out of the (possibly filtered) items list - the two are computed independently.
    SpaceLibraryLinks links = new SpaceLibraryLinks(true, List.of());

    SpaceLibraryAssociationListResponse response =
        SpaceLibraryAssociationResponseMapper.toListResponse(links);

    assertThat(response.getHasAssociations()).isTrue();
    assertThat(response.getItems()).isEmpty();
  }

  @Test
  void toListResponseMapsEveryItemInOrder() {
    SpaceLibraryLinks links =
        new SpaceLibraryLinks(
            true,
            List.of(
                new SpaceLibraryLink(association(), true, "A", null),
                new SpaceLibraryLink(association(), true, "B", null)));

    SpaceLibraryAssociationListResponse response =
        SpaceLibraryAssociationResponseMapper.toListResponse(links);

    assertThat(response.getItems())
        .extracting(SpaceLibraryAssociationResponse::getLibraryName)
        .containsExactly("A", "B");
  }

  @Test
  void toLibrarySpaceResponseCarriesTheSpaceNameAndNarrowerReaderCircleFlag() {
    SpaceAssetAssociation association = association();
    LibrarySpaceLink link = new LibrarySpaceLink(association, "Fachbereich", true, "Ada Lovelace");

    LibrarySpaceAssociationResponse response =
        SpaceLibraryAssociationResponseMapper.toLibrarySpaceResponse(link);

    assertThat(response.getSpaceId()).isEqualTo(association.getSpaceId());
    assertThat(response.getSpaceName()).isEqualTo("Fachbereich");
    assertThat(response.getCreatedByUserId()).isEqualTo(association.getCreatedByUserId());
    assertThat(response.getCreatedByDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getCreatedAt()).isEqualTo(association.getCreatedAt());
    assertThat(response.getNarrowerReaderCircle()).isTrue();
  }

  @Test
  void toLibrarySpaceResponsesReturnsAnEmptyListForNoLinksInsteadOfNull() {
    List<LibrarySpaceAssociationResponse> responses =
        SpaceLibraryAssociationResponseMapper.toLibrarySpaceResponses(List.of());

    assertThat(responses).isEmpty();
  }
}
