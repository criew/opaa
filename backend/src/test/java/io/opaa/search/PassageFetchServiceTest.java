package io.opaa.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fetch path without a Spring context: what it reads (and what it does not), and that quota and
 * alert are actually asked - the integration test cannot show the latter, because every scope
 * source it has carries no token.
 */
class PassageFetchServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final String HIT_ID = UUID.randomUUID().toString();

  private ChunkInspectionService chunks;
  private DocumentRepository documents;
  private SearchScopeSource scopeSource;
  private SearchHitAssembler hitAssembler;
  private AccessTokenQuota quota;
  private MassRetrievalAlarm alarm;
  private Document document;

  @BeforeEach
  void setUp() {
    chunks = mock(ChunkInspectionService.class);
    documents = mock(DocumentRepository.class);
    scopeSource = mock(SearchScopeSource.class);
    hitAssembler = mock(SearchHitAssembler.class);
    quota = mock(AccessTokenQuota.class);
    alarm = mock(MassRetrievalAlarm.class);

    document =
        new Document("akte.pdf", "/akte.pdf", "application/pdf", 10L, DocumentSourceType.UPLOAD);
    document.setLibraryId(LIBRARY_ID);
    document.setOrganizationId(ORGANIZATION_ID);
    when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
    when(hitAssembler.factsFor(any()))
        .thenReturn(new SearchHitAssembler.DocumentFacts("Akte", List.of()));
    when(scopeSource.scopeFor(any())).thenReturn(SearchRequestScope.ofPerson(Set.of(LIBRARY_ID)));
    when(chunks.findChunk(ORGANIZATION_ID, HIT_ID)).thenReturn(Optional.of(chunk(40)));
  }

  private PassageFetchService service(SearchProperties properties) {
    return new PassageFetchService(
        chunks, documents, scopeSource, hitAssembler, properties, quota, alarm);
  }

  private static SearchProperties properties(int contextPassages, int fetchMaxCharacters) {
    return new SearchProperties(
        10, 50, Math.min(1500, fetchMaxCharacters), fetchMaxCharacters, contextPassages);
  }

  private static ChunkInspection chunk(int index) {
    return new ChunkInspection(
        index == 40 ? HIT_ID : UUID.randomUUID().toString(),
        DOCUMENT_ID,
        "akte.pdf",
        LIBRARY_ID,
        "Akten",
        index,
        "Absatz " + index + ". ",
        Map.of(ChunkingService.LOCATION_METADATA_KEY, "Abschn. Teil A › Absatz " + index));
  }

  private static CurrentUser caller() {
    return CurrentUser.of(CALLER_ID, ORGANIZATION_ID, SystemRole.USER, null);
  }

  /**
   * Regression guard for the cap that only bounded the answer: the default path must read the index
   * window it returns, not every chunk of the document.
   */
  @Test
  void theDefaultPathReadsOnlyTheWindowAroundTheHit() {
    when(chunks.listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 39, 41))
        .thenReturn(List.of(chunk(39), chunk(40), chunk(41)));

    FetchedPassage fetched = service(properties(1, 200_000)).fetch(caller(), HIT_ID, false);

    verify(chunks).listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 39, 41);
    verify(chunks, never()).listDocumentChunks(any(), any());
    verify(chunks, never()).listChunkPage(any(), any(), any(), anyInt());
    assertThat(fetched.text()).contains("Absatz 39").contains("Absatz 41");
    assertThat(fetched.whole()).isFalse();
  }

  /**
   * Regression guard for the same defect on the whole-document path: the cap must stop the reading,
   * not trim an already materialized text.
   */
  @Test
  void aSmallCapStopsTheWholeDocumentWalkAfterTheFirstPage() {
    List<ChunkInspection> firstPage = new ArrayList<>();
    for (int index = 0; index < PassageFetchService.PAGE_SIZE; index++) {
      firstPage.add(chunk(index));
    }
    when(chunks.listChunkPage(
            eq(ORGANIZATION_ID), eq(DOCUMENT_ID), any(), eq(PassageFetchService.PAGE_SIZE)))
        .thenReturn(firstPage);

    FetchedPassage fetched = service(properties(1, 40)).fetch(caller(), HIT_ID, true);

    verify(chunks, times(1))
        .listChunkPage(
            eq(ORGANIZATION_ID), eq(DOCUMENT_ID), any(), eq(PassageFetchService.PAGE_SIZE));
    assertThat(fetched.text()).hasSizeLessThanOrEqualTo(40);
    assertThat(fetched.truncated()).isTrue();
    assertThat(fetched.characterLimit()).isEqualTo(40);
  }

  /** The corrected contract: truncated is not tied to whole. */
  @Test
  void theDefaultPathAlsoReportsTruncationWhenTheCapIsSmallerThanTheWindow() {
    when(chunks.listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 39, 41))
        .thenReturn(List.of(chunk(39), chunk(40), chunk(41)));

    FetchedPassage fetched = service(properties(1, 12)).fetch(caller(), HIT_ID, false);

    assertThat(fetched.whole()).isFalse();
    assertThat(fetched.truncated()).isTrue();
    assertThat(fetched.text()).hasSizeLessThanOrEqualTo(12);
  }

  /**
   * A document whose passages carry no {@code chunk_index} is in no index page - the whole-document
   * path would otherwise answer with an empty text instead of the passage it does have.
   */
  @Test
  void aHitWithoutAChunkIndexStillYieldsItsOwnText() {
    ChunkInspection unindexed =
        new ChunkInspection(
            HIT_ID,
            DOCUMENT_ID,
            "akte.pdf",
            LIBRARY_ID,
            "Akten",
            null,
            "Der ganze Text.",
            Map.of());
    when(chunks.findChunk(ORGANIZATION_ID, HIT_ID)).thenReturn(Optional.of(unindexed));

    FetchedPassage whole = service(properties(1, 200_000)).fetch(caller(), HIT_ID, true);

    assertThat(whole.text()).isEqualTo("Der ganze Text.");
    verify(chunks, never()).listChunkPage(any(), any(), any(), anyInt());
  }

  /**
   * A document that resolves no indexed passage at all falls back to the hit's own text rather than
   * to an empty one.
   */
  @Test
  void anEmptyWalkFallsBackToTheHitsOwnText() {
    when(chunks.listChunkPage(eq(ORGANIZATION_ID), eq(DOCUMENT_ID), any(), anyInt()))
        .thenReturn(List.of());

    FetchedPassage whole = service(properties(1, 200_000)).fetch(caller(), HIT_ID, true);

    assertThat(whole.text()).isEqualTo(chunk(40).content());
  }

  /**
   * The walk stops at the exact cap width, so nothing was cut - but a further page exists. That is
   * a truncation, and the probe read is what makes the flag say so.
   */
  @Test
  void aWalkEndingExactlyAtTheCapReportsTruncationWhenMoreRemains() {
    List<ChunkInspection> firstPage = new ArrayList<>();
    for (int index = 0; index < PassageFetchService.PAGE_SIZE; index++) {
      firstPage.add(chunk(index));
    }
    int exactWidth = PassageText.join(contentsOf(firstPage), 1_000_000).text().length();
    when(chunks.listChunkPage(
            eq(ORGANIZATION_ID), eq(DOCUMENT_ID), any(), eq(PassageFetchService.PAGE_SIZE)))
        .thenReturn(firstPage);
    when(chunks.listChunkPage(eq(ORGANIZATION_ID), eq(DOCUMENT_ID), any(), eq(1)))
        .thenReturn(List.of(chunk(PassageFetchService.PAGE_SIZE)));

    FetchedPassage whole = service(properties(1, exactWidth)).fetch(caller(), HIT_ID, true);

    assertThat(whole.text()).hasSize(exactWidth);
    assertThat(whole.truncated()).isTrue();
  }

  private static List<String> contentsOf(List<ChunkInspection> page) {
    return page.stream().map(ChunkInspection::content).toList();
  }

  @Test
  void quotaAndAlertSeeTheTokenOfTheRequest() {
    UUID tokenId = UUID.randomUUID();
    when(scopeSource.scopeFor(any()))
        .thenReturn(new SearchRequestScope(Set.of(LIBRARY_ID), tokenId));
    when(chunks.listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 39, 41))
        .thenReturn(List.of(chunk(40)));

    service(properties(1, 200_000)).fetch(caller(), HIT_ID, false);

    verify(quota).requireWithinQuota(tokenId);
    verify(alarm).record(ORGANIZATION_ID, tokenId);
  }

  @Test
  void anExhaustedQuotaStopsTheCallBeforeAnythingIsRead() {
    UUID tokenId = UUID.randomUUID();
    when(scopeSource.scopeFor(any()))
        .thenReturn(new SearchRequestScope(Set.of(LIBRARY_ID), tokenId));
    doThrow(new TooManyRequestsException(60)).when(quota).requireWithinQuota(tokenId);

    assertThatThrownBy(() -> service(properties(1, 200_000)).fetch(caller(), HIT_ID, false))
        .isInstanceOf(TooManyRequestsException.class);

    verifyNoInteractions(chunks);
    verifyNoInteractions(documents);
  }

  @Test
  void aHitOutsideTheEffectiveViewAnswersLikeAnUnknownOne() {
    when(scopeSource.scopeFor(any()))
        .thenReturn(SearchRequestScope.ofPerson(Set.of(UUID.randomUUID())));

    assertThatThrownBy(() -> service(properties(1, 200_000)).fetch(caller(), HIT_ID, false))
        .isInstanceOf(NotFoundException.class)
        .hasMessage(PassageFetchService.NOT_FOUND_MESSAGE);
  }

  @Test
  void theDownloadOfferIsDecidedAgainstTheScopeNotAgainstTheDocumentId() {
    when(chunks.listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 39, 41))
        .thenReturn(List.of(chunk(40)));

    assertThat(service(properties(1, 200_000)).fetch(caller(), HIT_ID, false).downloadable())
        .isTrue();
  }
}
