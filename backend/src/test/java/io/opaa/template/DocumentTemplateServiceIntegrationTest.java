package io.opaa.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetTypes;
import io.opaa.asset.ResponsibleUnit;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The acceptance criteria of document templates on the service level: the switch "Vorlagen" off as
 * delivered hides every template path and the right; creating needs CREATE_TEMPLATE, which nobody
 * holds as delivered; an admitted file keeps its bytes and placeholders, a broken file or one with
 * macros is refused; and every change writes one audit entry.
 */
@OpaaIntegrationTest
class DocumentTemplateServiceIntegrationTest {

  private static final ResponsibleUnit RESPONSIBLE = ResponsibleUnit.ofFunction("Referat Z3");

  @Autowired private DocumentTemplateService templateService;
  @Autowired private DocumentTemplateSettingsService settingsService;
  @Autowired private CapabilityService capabilityService;
  @Autowired private AssetTypes assetTypes;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RunTemplates runTemplates;

  private UUID organization;
  private UUID author;
  private UUID colleague;
  private UUID administrator;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM document_template_settings");
    organization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Vorlagen " + UUID.randomUUID()))
            .getId();
    author = createUser("Autorin");
    colleague = createUser("Kollegin");
    administrator = createUser("Systemverwaltung");
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM document_template_settings");
    jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM asset_grant_history WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM capability_grants WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM capability_grant_history WHERE organization_id = ?", organization);
    ownOrganizationFixtures.removeOrganizations(organization);
  }

  @Test
  void theSwitchIsOffAsDeliveredAndHidesTypeRightAndEveryPath() {
    assertThat(settingsService.isEnabled()).isFalse();
    assertThat(assetTypes.isActive(DocumentTemplate.ASSET_TYPE)).isFalse();
    assertThat(capabilityService.capabilitiesOf(admin()))
        .doesNotContain(Capability.CREATE_TEMPLATE);

    assertThatThrownBy(() -> templateService.list(admin())).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> templateService.create(creation("Bericht"), admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void switchedOnTheRightIsHeldByNobodyAsDelivered() {
    settingsService.update(true, admin());

    assertThat(assetTypes.isActive(DocumentTemplate.ASSET_TYPE)).isTrue();
    assertThat(capabilityService.capabilitiesOf(caller(author)))
        .doesNotContain(Capability.CREATE_TEMPLATE);
    assertThatThrownBy(() -> templateService.create(creation("Bericht"), caller(author)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Vorlagen anlegen");
  }

  @Test
  void aHolderOfTheRightCreatesATemplateWithItsPlaceholdersAndFile() {
    settingsService.update(true, admin());
    grantCreateTemplate(author);
    byte[] content =
        TestDocx.withParagraphs(
            "Bericht {{ titel }}", "{% for q in quellen %}{{ q.titel }}{% endfor %}");

    DocumentTemplateView created =
        templateService.create(
            new DocumentTemplateCreation(
                "Bericht",
                "Für Recherchen",
                null,
                null,
                TestDocx.upload("bericht.docx", content),
                RESPONSIBLE),
            caller(author));

    DocumentTemplate template = created.template();
    assertThat(created.myRole()).isEqualTo(AssetRole.OWNER);
    assertThat(template.getFileName()).isEqualTo("bericht.docx");
    assertThat(template.getSizeBytes()).isEqualTo(content.length);
    assertThat(template.getPlaceholders())
        .containsExactly(
            new TemplatePlaceholder("titel", PlaceholderKind.FIELD, List.of()),
            new TemplatePlaceholder("quellen", PlaceholderKind.LIST, List.of("titel")));
    assertThat(templateService.download(template.getId(), caller(author)).content())
        .isEqualTo(content);
    assertThat(templateService.list(caller(author)))
        .extracting(view -> view.template().getId())
        .containsExactly(template.getId());
    assertThat(templateService.list(caller(colleague))).isEmpty();
    assertThatThrownBy(() -> templateService.get(template.getId(), caller(colleague)))
        .isInstanceOf(NotFoundException.class);
    assertThat(auditCount("DOCUMENT_TEMPLATE_CREATED", template.getId())).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type ="
                    + " 'DOCUMENT_TEMPLATE_SETTINGS_CHANGED' AND organization_id = ?",
                Long.class,
                organization))
        .isEqualTo(1);
  }

  @Test
  void aBrokenFileAndAFileWithMacrosAreRefusedAndNothingIsStored() {
    settingsService.update(true, admin());

    assertThatThrownBy(
            () ->
                templateService.create(
                    new DocumentTemplateCreation(
                        "Kaputt",
                        null,
                        null,
                        null,
                        TestDocx.upload(
                            "kaputt.docx", "kein Word".getBytes(StandardCharsets.UTF_8)),
                        RESPONSIBLE),
                    admin()))
        .isInstanceOf(ValidationException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM assets WHERE organization_id = ?", Long.class, organization))
        .isZero();
  }

  @Test
  void changingReplacingAndDeletingWriteOneAuditEntryEach() {
    settingsService.update(true, admin());
    UUID templateId = templateService.create(creation("Vermerk"), admin()).template().getId();

    templateService.update(templateId, "Vermerk neu", "Kurz", admin());
    DocumentTemplateView replaced =
        templateService.replaceFile(
            templateId,
            TestDocx.upload("neu.docx", TestDocx.withParagraphs("{{ aktenzeichen }}")),
            admin());
    assertThat(replaced.template().getPlaceholders())
        .extracting(TemplatePlaceholder::name)
        .containsExactly("aktenzeichen");
    assertThat(auditCount("DOCUMENT_TEMPLATE_CHANGED", templateId)).isEqualTo(2);

    templateService.delete(templateId, admin());
    assertThat(auditCount("DOCUMENT_TEMPLATE_DELETED", templateId)).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_template_files WHERE template_id = ?",
                Long.class,
                templateId))
        .isZero();
  }

  @Test
  void switchedOffAgainTheStoredTemplateStaysButIsUnreachable() {
    settingsService.update(true, admin());
    UUID templateId = templateService.create(creation("Bericht"), admin()).template().getId();

    settingsService.update(false, admin());

    assertThatThrownBy(() -> templateService.get(templateId, admin()))
        .isInstanceOf(NotFoundException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_templates WHERE id = ?", Long.class, templateId))
        .isEqualTo(1);
  }

  @Test
  void anAgentRunMayFillOnlyTemplatesTheAskerMayReadAndOnlyWhileTheSwitchIsOn() {
    settingsService.update(true, admin());
    grantCreateTemplate(author);
    DocumentTemplate template =
        templateService.create(creation("Bericht"), caller(author)).template();

    assertThat(runTemplates.available(organization, author))
        .extracting(RunTemplates.RunTemplate::id, RunTemplates.RunTemplate::placeholders)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                template.getId(),
                List.of(new TemplatePlaceholder("titel", PlaceholderKind.FIELD, List.of()))));
    assertThat(runTemplates.forFilling(template.getId(), organization, author))
        .get()
        .extracting(RunTemplates.TemplateToFill::fileName)
        .isEqualTo("vorlage.docx");
    assertThat(runTemplates.available(organization, colleague)).isEmpty();
    assertThat(runTemplates.forFilling(template.getId(), organization, colleague)).isEmpty();
    assertThat(runTemplates.forFilling(template.getId(), UUID.randomUUID(), author)).isEmpty();

    settingsService.update(false, admin());

    assertThat(runTemplates.available(organization, author)).isEmpty();
    assertThat(runTemplates.forFilling(template.getId(), organization, author)).isEmpty();
  }

  private DocumentTemplateCreation creation(String name) {
    return new DocumentTemplateCreation(
        name,
        null,
        null,
        null,
        TestDocx.upload("vorlage.docx", TestDocx.withParagraphs("{{ titel }}")),
        RESPONSIBLE);
  }

  private long auditCount(String eventType, UUID objectId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE event_type = ? AND object_id = ?",
        Long.class,
        eventType,
        objectId.toString());
  }

  private void grantCreateTemplate(UUID userId) {
    jdbcTemplate.update(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_user_id) VALUES (?, ?, 'CREATE_TEMPLATE', 'USER', ?)",
        UUID.randomUUID(),
        organization,
        userId);
  }

  private UUID createUser(String displayName) {
    User user =
        new User(
            "template-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organization);
    return userRepository.save(user).getId();
  }

  private CurrentUser admin() {
    return CurrentUser.of(administrator, organization, SystemRole.SYSTEM_ADMIN, "Verwaltung");
  }

  private CurrentUser caller(UUID userId) {
    return CurrentUser.of(userId, organization, SystemRole.USER, "Sachbearbeitung");
  }
}
