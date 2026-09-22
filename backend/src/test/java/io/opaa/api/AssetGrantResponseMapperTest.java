package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.AssetGrantView;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.GroupSizeSignal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities/records - the mapper
 * counterpart of {@code SpaceResponseMapperTest} (#860): pins {@link AssetGrantResponseMapper}'s
 * field-by-field behaviour, including {@code null}-vs-absent display names.
 */
class AssetGrantResponseMapperTest {

  @Test
  void toResponseCopiesGrantFieldsAndTheResolvedDisplayNames() {
    UUID libraryId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID grantedByUserId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(3600);
    AssetGrant grant =
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            libraryId,
            organizationId,
            subjectId,
            AssetRole.MANAGER,
            expiresAt,
            grantedByUserId);
    AssetGrantView view = AssetGrantView.ofUser(grant, "Subjekt Person", "Erteilende Person");

    AssetGrantResponse response = AssetGrantResponseMapper.toResponse(view);

    assertThat(response.getId()).isEqualTo(grant.getId());
    assertThat(response.getSubjectType()).isEqualTo(PermissionSubjectType.USER);
    assertThat(response.getSubjectId()).isEqualTo(subjectId);
    assertThat(response.getRole()).isEqualTo(AssetRole.MANAGER);
    assertThat(response.getCreatedAt()).isEqualTo(grant.getCreatedAt());
    assertThat(response.getUpdatedAt()).isEqualTo(grant.getUpdatedAt());
    assertThat(response.getSubjectDisplayName()).isEqualTo("Subjekt Person");
    assertThat(response.getExpiresAt()).isEqualTo(expiresAt);
    assertThat(response.getGrantedByUserId()).isEqualTo(grantedByUserId);
    assertThat(response.getGrantedByDisplayName()).isEqualTo("Erteilende Person");
  }

  @Test
  void toResponseAllowsNullDisplayNamesForADeletedSubjectOrGranter() {
    AssetGrant grant =
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AssetRole.VIEWER,
            null,
            null,
            null);
    AssetGrantView view = AssetGrantView.ofGroup(grant, null, null, false, GroupSizeSignal.NONE);

    AssetGrantResponse response = AssetGrantResponseMapper.toResponse(view);

    assertThat(response.getSubjectDisplayName()).isNull();
    assertThat(response.getGrantedByUserId()).isNull();
    assertThat(response.getGrantedByDisplayName()).isNull();
    assertThat(response.getExpiresAt()).isNull();
  }

  @Test
  void toResponsesMapsEveryViewInOrder() {
    AssetGrant first =
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AssetRole.VIEWER,
            null,
            null);
    AssetGrant second =
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AssetRole.OWNER,
            null,
            null);
    List<AssetGrantView> views =
        List.of(
            AssetGrantView.ofUser(first, "First", null),
            AssetGrantView.ofUser(second, "Second", null));

    List<AssetGrantResponse> responses = AssetGrantResponseMapper.toResponses(views);

    assertThat(responses)
        .extracting(AssetGrantResponse::getSubjectDisplayName)
        .containsExactly("First", "Second");
  }

  /** #1820: the growth signal of ADR-0036, Entscheidung 9 beside a group's release. */
  @Test
  void toResponseCarriesTheGrowthSignalOfAGroupGrant() {
    AssetGrant grant =
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AssetRole.VIEWER,
            null,
            UUID.randomUUID(),
            23);
    AssetGrantView view =
        AssetGrantView.ofGroup(
            grant, "Referat 50", "Erteilende Person", false, GroupSizeSignal.of(23, 41, 5));

    AssetGrantResponse response = AssetGrantResponseMapper.toResponse(view);

    assertThat(response.getSubjectDisplayName()).isEqualTo("Referat 50");
    assertThat(response.getMemberCountAtGrant()).isEqualTo(23);
    assertThat(response.getMemberCountNow()).isEqualTo(41);
    assertThat(response.getSmallGroup()).isFalse();
    assertThat(response.getEmptyGroup()).isFalse();
    assertThat(response.getProtectedGroup()).isFalse();
  }

  /** A person's grant carries no group figures at all. */
  @Test
  void toResponseLeavesEveryGroupFigureUnsetForAPerson() {
    AssetGrant grant =
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AssetRole.VIEWER,
            null,
            null);

    AssetGrantResponse response =
        AssetGrantResponseMapper.toResponse(AssetGrantView.ofUser(grant, "Person", null));

    assertThat(response.getProtectedGroup()).isNull();
    assertThat(response.getSmallGroup()).isNull();
    assertThat(response.getEmptyGroup()).isNull();
    assertThat(response.getMemberCountAtGrant()).isNull();
    assertThat(response.getMemberCountNow()).isNull();
  }

  /** ADR-0036/9: a protected group is nameless here too, and carries no figure at all. */
  @Test
  void toResponseLeavesAProtectedGroupNamelessAndWithoutASignal() {
    AssetGrant grant =
        AssetGrant.forGroup(
            KnowledgeLibrary.ASSET_TYPE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AssetRole.VIEWER,
            null,
            null,
            12);
    AssetGrantView view =
        AssetGrantView.ofGroup(grant, "Personalrat", null, true, GroupSizeSignal.NONE);

    AssetGrantResponse response = AssetGrantResponseMapper.toResponse(view);

    assertThat(response.getSubjectDisplayName()).isNull();
    assertThat(response.getProtectedGroup()).isTrue();
    assertThat(response.getSmallGroup()).isNull();
    assertThat(response.getEmptyGroup()).isNull();
    assertThat(response.getMemberCountAtGrant()).isNull();
    assertThat(response.getMemberCountNow()).isNull();
  }

  @Test
  void toUpsertCopiesEveryRequestField() {
    UUID subjectId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(60);
    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.GROUP, subjectId, AssetRole.EDITOR)
            .expiresAt(expiresAt);

    AssetGrantUpsert upsert = AssetGrantResponseMapper.toUpsert(request);

    assertThat(upsert.subjectType()).isEqualTo(PermissionSubjectType.GROUP);
    assertThat(upsert.subjectId()).isEqualTo(subjectId);
    assertThat(upsert.role()).isEqualTo(AssetRole.EDITOR);
    assertThat(upsert.expiresAt()).isEqualTo(expiresAt);
  }
}
