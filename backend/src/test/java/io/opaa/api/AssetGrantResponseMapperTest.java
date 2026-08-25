package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.AssetGrantView;
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
            libraryId, organizationId, subjectId, AssetRole.MANAGER, expiresAt, grantedByUserId);
    AssetGrantView view = new AssetGrantView(grant, "Subjekt Person", "Erteilende Person");

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
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AssetRole.VIEWER, null, null);
    AssetGrantView view = new AssetGrantView(grant, null, null);

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
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AssetRole.VIEWER, null, null);
    AssetGrant second =
        AssetGrant.forUser(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AssetRole.OWNER, null, null);
    List<AssetGrantView> views =
        List.of(
            new AssetGrantView(first, "First", null), new AssetGrantView(second, "Second", null));

    List<AssetGrantResponse> responses = AssetGrantResponseMapper.toResponses(views);

    assertThat(responses)
        .extracting(AssetGrantResponse::getSubjectDisplayName)
        .containsExactly("First", "Second");
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
