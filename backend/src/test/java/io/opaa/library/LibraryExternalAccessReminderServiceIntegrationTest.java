package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.PublicBaseUrl;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.SendResult;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Die Wiedervorlage vor dem Ablauf einer Fremdzugangsfreigabe (#1731): dass sie zugestellt wird,
 * dass sie je Freigabe genau einmal geht, dass eine erneuerte Freigabe wieder eine verdient, und
 * dass sie eine bereits abgelaufene Freigabe nicht mehr ankündigt.
 *
 * <p>{@link MailService} ist hier ein Mock, der einer lokal gebauten zweiten Instanz des Dienstes
 * mitgegeben wird - zusammen mit einer gestellten Uhr, wie es der Ablauflauf in {@link
 * LibraryExternalAccessServiceIntegrationTest} schon tut. Ein klassenlokales {@code @MockitoBean}
 * spaltete den Spring-Kontext (AGENTS.md, „Spring-Testkontexte"); die echte Bean bleibt für alle
 * anderen Klassen unberührt.
 */
@OpaaIntegrationTest
class LibraryExternalAccessReminderServiceIntegrationTest {

  @Autowired private LibraryExternalAccessService externalAccessService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;

  @Autowired
  private io.opaa.permission.GroupMembershipHistoryRepository membershipHistoryRepository;

  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private PublicBaseUrl publicBaseUrl;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private MailService mail;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdGroupIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    mail = mock(MailService.class);
    when(mail.send(any(), any(), anyString(), any()))
        .thenReturn(new SendResult.Sent("user@example.com"));
  }

  @AfterEach
  void tearDown() {
    List<KnowledgeLibrary> ownLibraries =
        libraryRepository.findAll().stream()
            .filter(
                library ->
                    createdUserIds.contains(library.getOwnerUserId())
                        || createdGroupIds.contains(library.getOwnerGroupId()))
            .toList();
    libraryRepository.deleteAll(ownLibraries);
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    for (UUID groupId : createdGroupIds) {
      groupRepository.deleteById(groupId);
    }
    // Since #1819 a library carries ownership intervals; their owner column is RESTRICT, so
    // they have to go before the accounts that hold them.
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  /**
   * Das Abnahmekriterium: die Erinnerung erreicht den Verantwortlichen über die bestehende
   * Mailmechanik, mit Bibliothek und Ablaufdatum - und ohne eine Angabe darüber, wer die Bibliothek
   * in einem Zugangstoken führt.
   */
  @Test
  void theResponsiblePersonIsRemindedOnceWithTheLibraryAndItsExpiry() {
    UUID owner = createUser("Erika Mustermann");
    UUID libraryId = createLibrary(owner);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, expiresAt);

    int sent = reminderServiceAt(expiresAt.minus(10, ChronoUnit.DAYS), 14).runOnce();

    assertThat(sent).isEqualTo(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
    verify(mail)
        .send(
            eq(MailTemplateKey.EXTERNAL_ACCESS_RELEASE_EXPIRING),
            eq(Locale.GERMAN),
            eq("user@example.com"),
            variables.capture());
    assertThat(variables.getValue())
        .containsEntry("displayName", "Erika Mustermann")
        .containsEntry("libraryName", "Bibliothek")
        .containsKey("expiresAtDate");
    assertThat(variables.getValue()).doesNotContainKey("tokenCount");
    assertThat(
            libraryRepository.findById(libraryId).orElseThrow().getExternalAccessReminderSentAt())
        .isNotNull();
  }

  /** Höchstens eine Mail je Freigabe - sonst erinnert der tägliche Lauf jeden Tag des Fensters. */
  @Test
  void aSecondRunInsideTheSameWindowSendsNothingMore() {
    UUID owner = createUser("Erika Mustermann");
    UUID libraryId = createLibrary(owner);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, expiresAt);
    Instant insideTheWindow = expiresAt.minus(10, ChronoUnit.DAYS);
    reminderServiceAt(insideTheWindow, 14).runOnce();

    assertThat(reminderServiceAt(insideTheWindow.plus(1, ChronoUnit.DAYS), 14).runOnce()).isZero();
    verify(mail, org.mockito.Mockito.times(1)).send(any(), any(), anyString(), any());
  }

  /** Eine erneuerte Freigabe ist eine neue Entscheidung und verdient ihre eigene Wiedervorlage. */
  @Test
  void renewingTheReleaseClearsTheMarkerSoTheNextExpiryIsAnnouncedAgain() {
    UUID owner = createUser("Erika Mustermann");
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    reminderServiceAt(Instant.now().plus(20, ChronoUnit.DAYS), 14).runOnce();

    Instant renewedUntil = Instant.now().plus(90, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, renewedUntil);

    assertThat(
            libraryRepository.findById(libraryId).orElseThrow().getExternalAccessReminderSentAt())
        .isNull();
    assertThat(reminderServiceAt(renewedUntil.minus(10, ChronoUnit.DAYS), 14).runOnce())
        .isEqualTo(1);
  }

  /**
   * Der Lauf kündigt keine Frist an, die schon abgelaufen ist - der Ablauflauf desselben Ticks hat
   * sie bereits außer Kraft gesetzt, und eine Mail „läuft ab" über etwas Erloschenes ist falsch.
   */
  @Test
  void anAlreadyExpiredReleaseIsNotAnnounced() {
    UUID owner = createUser("Erika Mustermann");
    UUID libraryId = createLibrary(owner);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, expiresAt);

    assertThat(reminderServiceAt(expiresAt.plus(1, ChronoUnit.DAYS), 14).runOnce()).isZero();
    verify(mail, never()).send(any(), any(), anyString(), any());
  }

  /** Eine Freigabe außerhalb des Fensters wird nicht vorzeitig angekündigt. */
  @Test
  void aReleaseBeyondTheWindowIsNotAnnouncedYet() {
    UUID owner = createUser("Erika Mustermann");
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));

    assertThat(reminderServiceAt(Instant.now(), 14).runOnce()).isZero();
    verify(mail, never()).send(any(), any(), anyString(), any());
  }

  /**
   * {@code reminderLeadDays == 0} schaltet die Wiedervorlage ab; die Freigabe erlischt trotzdem.
   */
  @Test
  void aLeadOfZeroDaysSwitchesTheReminderOff() {
    UUID owner = createUser("Erika Mustermann");
    UUID libraryId = createLibrary(owner);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, expiresAt);

    assertThat(reminderServiceAt(expiresAt.minus(1, ChronoUnit.DAYS), 0).runOnce()).isZero();
    verify(mail, never()).send(any(), any(), anyString(), any());
    assertThat(
            libraryRepository.findById(libraryId).orElseThrow().getExternalAccessReminderSentAt())
        .isNull();
  }

  /**
   * Der Empfänger ist die Person, die die Freigabe gesetzt hat. Ist ihr Konto weg und trägt die
   * Bibliothek keinen benannten Personen-Eigentümer, wird übersprungen statt geraten - der Marker
   * wird trotzdem gesetzt, damit der Lauf es nicht täglich erneut versucht.
   */
  @Test
  void withoutAReachableResponsiblePersonTheRunSkipsInsteadOfGuessing() {
    UUID setter = createUser("Erika Mustermann");
    UUID libraryId = createGroupOwnedLibrary(setter);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(
        currentUserOf(setter, true), libraryId, true, expiresAt);
    // das Konto des Setzenden ist weg; die Spalte traegt bewusst keinen Fremdschluessel
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET external_access_set_by_user_id = NULL WHERE id = ?",
        libraryId);

    assertThat(reminderServiceAt(expiresAt.minus(1, ChronoUnit.DAYS), 14).runOnce()).isZero();
    verify(mail, never()).send(any(), any(), anyString(), any());
    assertThat(
            libraryRepository.findById(libraryId).orElseThrow().getExternalAccessReminderSentAt())
        .isNotNull();
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  /**
   * Der Erinnerungslauf mit gestellter Uhr und gestelltem Vorlauf. Eine zweite Instanz statt der
   * Bean des Kontexts: die Produktionsbean liest die Wanduhr und die ausgelieferte Property, und
   * ein klassenlokaler Ersatz spaltete den Kontext.
   */
  private LibraryExternalAccessReminderService reminderServiceAt(Instant now, int leadDays) {
    return new LibraryExternalAccessReminderService(
        libraryRepository,
        userRepository,
        mail,
        publicBaseUrl,
        new ExternalAccessProperties(365, leadDays),
        () -> now,
        ZoneId.of("Europe/Berlin"));
  }

  private UUID createUser(String displayName) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", displayName);
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private UUID createLibrary(UUID ownerId) {
    return libraryService
        .createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(AssetOwnerType.USER)
                .ownerId(ownerId)
                .build(),
            currentUserOf(ownerId))
        .library()
        .getId();
  }

  /**
   * Eine Bibliothek, die eine Gruppe verantwortet - der Fall, in dem der Empfaengerfallback ins
   * Leere laeuft, sobald auch das Konto des Setzenden weg ist.
   */
  private UUID createGroupOwnedLibrary(UUID creator) {
    Group group =
        new Group(
            organizationId,
            GroupKind.AD_HOC,
            "Referat " + UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null);
    group.addMembership(new GroupMembership(creator, organizationId));
    group = groupRepository.save(group);
    createdGroupIds.add(group.getId());
    return libraryService
        .createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(AssetOwnerType.GROUP)
                .ownerId(group.getId())
                .build(),
            currentUserOf(creator, true))
        .library()
        .getId();
  }

  private CurrentUser currentUserOf(UUID userId) {
    return currentUserOf(userId, false);
  }

  private CurrentUser currentUserOf(UUID userId, boolean systemAdmin) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        userId,
        user.getOrganizationId(),
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        user.getDisplayName());
  }
}
