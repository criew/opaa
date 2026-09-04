package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.metadata.BulkMetadataResult;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.DocumentMetadataCorrectionService;
import io.opaa.indexing.metadata.DocumentMetadataFieldView;
import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.LibraryMetadataMaintenance;
import io.opaa.indexing.metadata.LibraryMetadataMaintenanceService;
import io.opaa.indexing.metadata.MetadataFieldMaintenance;
import io.opaa.indexing.metadata.MetadataValueInput;
import io.opaa.indexing.metadata.MetadataValueSnapshot;
import io.opaa.indexing.metadata.MetadataValueState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Controller wiring for the metadata correction endpoints (#1068): request bodies reach {@link
 * DocumentMetadataCorrectionService} as domain input, its outcomes and exceptions become the
 * documented status codes (VIEWER 403, no access/foreign organization 404, invalid value 400).
 */
@WebMvcTest(DocumentMetadataController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class DocumentMetadataControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DocumentMetadataCorrectionService correctionService;
  @MockitoBean private LibraryMetadataMaintenanceService maintenanceService;
  @MockitoBean private UserService userService;

  private final UUID libraryId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final UUID currentUserId = UUID.randomUUID();
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    setId(user, currentUserId);
    caller =
        CurrentUser.of(
            user.getId(), user.getOrganizationId(), user.getSystemRole(), user.getDisplayName());
    when(userService.findOrCreateUser(eq(TEST_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(user);
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private String metadataPath() {
    return "/api/v1/libraries/" + libraryId + "/documents/" + documentId + "/metadata";
  }

  @Test
  void readingTheMetadataListsEveryFieldWithItsProvenance() throws Exception {
    MetadataValueSnapshot manual =
        new MetadataValueSnapshot(
            "document_type",
            MetadataValueState.SET,
            null,
            "VERMERK",
            null,
            null,
            MetadataOrigin.MANUAL,
            null,
            null,
            null,
            currentUserId,
            Instant.parse("2026-09-04T10:00:00Z"));
    when(correctionService.fieldsOf(libraryId, documentId, caller))
        .thenReturn(
            List.of(
                new DocumentMetadataFieldView(CoreMetadataField.TITLE, null, null, null),
                new DocumentMetadataFieldView(
                    CoreMetadataField.DOCUMENT_TYPE, manual, "Vermerk", "Test User"),
                new DocumentMetadataFieldView(CoreMetadataField.DOCUMENT_DATE, null, null, null)));

    mockMvc
        .perform(get(metadataPath()).with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.fields.length()").value(3))
        .andExpect(jsonPath("$.fields[0].fieldKey").value("title"))
        .andExpect(jsonPath("$.fields[0].value").doesNotExist())
        .andExpect(jsonPath("$.fields[1].value").value("VERMERK"))
        .andExpect(jsonPath("$.fields[1].displayValue").value("Vermerk"))
        .andExpect(jsonPath("$.fields[1].origin").value("MANUAL"))
        .andExpect(jsonPath("$.fields[1].actorDisplayName").value("Test User"))
        .andExpect(jsonPath("$.fields[1].actorUserId").value(currentUserId.toString()));
  }

  @Test
  void settingAValueHandsTheParsedInputToTheServiceAndReturnsTheField() throws Exception {
    MetadataValueSnapshot after =
        new MetadataValueSnapshot(
            "document_date",
            MetadataValueState.SET,
            null,
            null,
            LocalDate.of(2024, 1, 1),
            DatePrecision.YEAR,
            MetadataOrigin.MANUAL,
            null,
            null,
            null,
            currentUserId,
            Instant.parse("2026-09-04T10:00:00Z"));
    when(correctionService.setValue(
            eq(libraryId), eq(documentId), eq("document_date"), any(), eq(caller)))
        .thenReturn(
            new DocumentMetadataFieldView(
                CoreMetadataField.DOCUMENT_DATE, after, "2024", "Test User"));

    mockMvc
        .perform(
            put(metadataPath() + "/document_date")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateValue\":\"2024-05-17\",\"datePrecision\":\"YEAR\"}")
                .with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fieldKey").value("document_date"))
        .andExpect(jsonPath("$.value").value("2024-01-01"))
        .andExpect(jsonPath("$.displayValue").value("2024"))
        .andExpect(jsonPath("$.datePrecision").value("YEAR"))
        .andExpect(jsonPath("$.origin").value("MANUAL"));

    ArgumentCaptor<MetadataValueInput> input = ArgumentCaptor.forClass(MetadataValueInput.class);
    verify(correctionService)
        .setValue(eq(libraryId), eq(documentId), eq("document_date"), input.capture(), eq(caller));
    org.assertj.core.api.Assertions.assertThat(input.getValue())
        .isEqualTo(MetadataValueInput.date(LocalDate.of(2024, 5, 17), DatePrecision.YEAR));
  }

  @Test
  void anUnparsableDateIsRejectedBeforeTheServiceIsCalled() throws Exception {
    mockMvc
        .perform(
            put(metadataPath() + "/document_date")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateValue\":\"2024-13-40\",\"datePrecision\":\"DAY\"}")
                .with(asTestUser()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aVocabularyRejectionBecomes400() throws Exception {
    when(correctionService.setValue(any(), any(), any(), any(), any()))
        .thenThrow(new ValidationException("Unbekannte Dokumentart: X"));

    mockMvc
        .perform(
            put(metadataPath() + "/document_type")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vocabularyCode\":\"X\"}")
                .with(asTestUser()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Unbekannte Dokumentart: X"));
  }

  @Test
  void aReaderWithoutEditRightGets403AndNoAccessOrForeignOrganizationGets404() throws Exception {
    when(correctionService.setValue(any(), any(), any(), any(), any()))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));
    mockMvc
        .perform(
            put(metadataPath() + "/title")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"textValue\":\"Neu\"}")
                .with(asTestUser()))
        .andExpect(status().isForbidden());

    when(correctionService.fieldsOf(any(), any(), any()))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));
    mockMvc.perform(get(metadataPath()).with(asTestUser())).andExpect(status().isNotFound());
  }

  @Test
  void deletingAValueAnswers204() throws Exception {
    mockMvc
        .perform(delete(metadataPath() + "/title").with(asTestUser()))
        .andExpect(status().isNoContent());

    verify(correctionService).deleteValue(libraryId, documentId, "title", caller);
  }

  @Test
  void aBulkAssignmentReturnsCountersAndRejectedIds() throws Exception {
    UUID first = UUID.randomUUID();
    UUID rejected = UUID.randomUUID();
    when(correctionService.bulkSetValue(
            eq(libraryId), eq("document_type"), any(), eq(List.of(first, rejected)), eq(caller)))
        .thenReturn(new BulkMetadataResult(1, 0, List.of(rejected), "metadata-bulk-1"));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/documents/metadata/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"fieldKey\":\"document_type\",\"value\":{\"vocabularyCode\":\"VERMERK\"},"
                        + "\"documentIds\":[\""
                        + first
                        + "\",\""
                        + rejected
                        + "\"]}")
                .with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(1))
        .andExpect(jsonPath("$.unchangedCount").value(0))
        .andExpect(jsonPath("$.rejectedDocumentIds[0]").value(rejected.toString()))
        .andExpect(jsonPath("$.correlationRef").value("metadata-bulk-1"));

    ArgumentCaptor<MetadataValueInput> input = ArgumentCaptor.forClass(MetadataValueInput.class);
    verify(correctionService)
        .bulkSetValue(eq(libraryId), eq("document_type"), input.capture(), any(), eq(caller));
    org.assertj.core.api.Assertions.assertThat(input.getValue())
        .isEqualTo(MetadataValueInput.vocabulary("VERMERK"));
  }

  @Test
  void aBulkAssignmentWithoutDocumentsIsRejectedByBeanValidation() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/documents/metadata/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"fieldKey\":\"document_type\",\"value\":{\"vocabularyCode\":\"VERMERK\"},"
                        + "\"documentIds\":[]}")
                .with(asTestUser()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theVocabularyIsListedInDisplayOrder() throws Exception {
    when(correctionService.vocabulary())
        .thenReturn(
            List.of(
                new DocumentTypeVocabularyEntry("SATZUNG_ORDNUNG", "Satzung/Ordnung", 10, Set.of()),
                new DocumentTypeVocabularyEntry("VERMERK", "Vermerk", 30, Set.of())));

    mockMvc
        .perform(get("/api/v1/metadata/document-types").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].code").value("SATZUNG_ORDNUNG"))
        .andExpect(jsonPath("$.items[0].label").value("Satzung/Ordnung"))
        .andExpect(jsonPath("$.items[1].code").value("VERMERK"));
  }

  @Test
  void theThirdStateIsSetThroughTheSameOperationWithoutAValue() throws Exception {
    MetadataValueSnapshot after =
        new MetadataValueSnapshot(
            "document_date",
            MetadataValueState.NOT_DETERMINABLE,
            null,
            null,
            null,
            null,
            MetadataOrigin.MANUAL,
            null,
            null,
            null,
            currentUserId,
            Instant.parse("2026-09-04T10:00:00Z"));
    when(correctionService.setValue(
            eq(libraryId), eq(documentId), eq("document_date"), any(), eq(caller)))
        .thenReturn(
            new DocumentMetadataFieldView(
                CoreMetadataField.DOCUMENT_DATE, after, null, "Test User"));

    mockMvc
        .perform(
            put(metadataPath() + "/document_date")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"NOT_DETERMINABLE\"}")
                .with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NOT_DETERMINABLE"))
        .andExpect(jsonPath("$.value").doesNotExist())
        .andExpect(jsonPath("$.origin").value("MANUAL"));

    ArgumentCaptor<MetadataValueInput> input = ArgumentCaptor.forClass(MetadataValueInput.class);
    verify(correctionService)
        .setValue(eq(libraryId), eq(documentId), eq("document_date"), input.capture(), eq(caller));
    org.assertj.core.api.Assertions.assertThat(input.getValue())
        .isEqualTo(MetadataValueInput.notDeterminable());
  }

  @Test
  void theAnchorIsReadableByEveryoneWhoMayReadTheLibraryAndAbsentOtherwise() throws Exception {
    when(maintenanceService.maintenanceOf(libraryId, caller))
        .thenReturn(
            new LibraryMetadataMaintenance(
                libraryId,
                10,
                List.of(
                    new MetadataFieldMaintenance(CoreMetadataField.TITLE, 10, 10, 0),
                    new MetadataFieldMaintenance(CoreMetadataField.DOCUMENT_TYPE, 10, 4, 2),
                    new MetadataFieldMaintenance(CoreMetadataField.DOCUMENT_DATE, 10, 0, 0))));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/metadata/maintenance").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalDocuments").value(10))
        .andExpect(jsonPath("$.fields[1].fieldKey").value("document_type"))
        .andExpect(jsonPath("$.fields[1].documentsWithoutValue").value(4))
        .andExpect(jsonPath("$.fields[1].notDeterminableDocuments").value(2))
        .andExpect(jsonPath("$.fields[1].missingShare").value(0.4))
        .andExpect(jsonPath("$.fields[2].documentsWithoutValue").value(10));

    UUID foreign = UUID.randomUUID();
    when(maintenanceService.maintenanceOf(eq(foreign), any()))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));
    mockMvc
        .perform(get("/api/v1/libraries/" + foreign + "/metadata/maintenance").with(asTestUser()))
        .andExpect(status().isNotFound());
  }
}
