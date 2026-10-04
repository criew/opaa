package io.opaa.query.citation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.SourceFreezeReason;
import io.opaa.api.types.MetadataFilterMatch;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectorStubs;
import io.opaa.indexing.source.SourceStateLookup;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.metadata.CitationMetadataReader;
import io.opaa.metadata.CoreMetadata;
import io.opaa.metadata.CoreMetadataChunkKeys;
import io.opaa.metadata.DocumentMetadataService;
import io.opaa.metadata.MetadataFilter;
import io.opaa.query.citation.CitationValidator.ValidatedCitation;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ChatSourceAssemblerTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final DocumentMetadataService documentMetadataService =
      mock(DocumentMetadataService.class);
  private final ChatSourceAssembler assembler =
      new ChatSourceAssembler(
          documentRepository,
          documentMetadataService,
          mock(CitationMetadataReader.class),
          mock(KnowledgeLibraryRepository.class),
          SourceConnectorStubs.registry(),
          libraries -> java.util.Map.of(),
          mock(IndexingJobRepository.class));

  private static Document chunk(String fileName, String documentId, String text, double score) {
    return Document.builder()
        .text(text)
        .metadata(Map.of("file_name", fileName, "document_id", documentId))
        .score(score)
        .build();
  }

  private static final Document README = chunk("readme.md", "doc-123", "Relevant content", 0.85);

  private static ValidatedCitation citation(String documentId, String fileName, boolean valid) {
    return new ValidatedCitation(documentId, 0, fileName, valid);
  }

  /**
   * {@code relevanceScore} means the same thing no matter which search path found the chunk: the
   * reciprocal of the source's own rank in the selection, never a raw {@link Document#getScore()}.
   */
  @Nested
  class RelevanceScoreAcrossSearchPaths {

    /**
     * A lone vector hit (cosine 0.8) and a lone lexical hit (ts_rank 0.09) arrive in fused order;
     * the exposed scores follow that order (1.0, 0.5) instead of the incomparable raw scores, which
     * would have dropped the lexical hit to the bottom of the evidence list.
     */
    @Test
    void aLexicalOnlyChunkKeepsTheRelevanceScoreOfItsFusedRank() {
      var vectorChunk = chunk("vector.md", "doc-vector", "vector hit", 0.8);
      var lexicalChunk = chunk("lexical.md", "doc-lexical", "literal term", 0.09);

      List<ChatSource> sources =
          assembler.assemble(List.of(vectorChunk, lexicalChunk), List.of(), MetadataFilter.NONE);

      assertThat(sources)
          .extracting(ChatSource::getFileName, ChatSource::getRelevanceScore)
          .containsExactly(tuple("vector.md", 1.0), tuple("lexical.md", 0.5));
    }

    /**
     * The rank is a source's own position, not its best chunk's: a document contributing two of the
     * three selected chunks occupies one row, and the next document is rank 2 - never rank 3, which
     * would label a two-row list "Rang 1" and "Rang 3".
     */
    @Test
    void ranksSourcesByTheirOwnPositionWhenOneDocumentContributesSeveralChunks() {
      var firstChunkOfA = chunk("a.md", "doc-a", "A, erster Abschnitt", 0.9);
      var secondChunkOfA = chunk("a.md", "doc-a", "A, zweiter Abschnitt", 0.85);
      var chunkOfB = chunk("b.md", "doc-b", "B, einziger Abschnitt", 0.8);

      List<ChatSource> sources =
          assembler.assemble(
              List.of(firstChunkOfA, secondChunkOfA, chunkOfB), List.of(), MetadataFilter.NONE);

      assertThat(sources)
          .extracting(ChatSource::getFileName, ChatSource::getRelevanceScore)
          .containsExactly(tuple("a.md", 1.0), tuple("b.md", 0.5));
    }
  }

  /** Synthetic entries for invalid citations whose document id matches no retrieved chunk. */
  @Nested
  class OrphanCitations {

    /**
     * A synthetic entry has no underlying document to resolve - {@code documentId}, {@code
     * sourceType} and {@code sourceUrl} stay null, there is nothing real to link to.
     */
    @Test
    void synthesizesNoDocumentLinkForAFabricatedCitation() {
      List<ChatSource> sources =
          assembler.assemble(
              List.of(),
              List.of(citation("nonexistent-doc", "fabricated.pdf", false)),
              MetadataFilter.NONE);

      ChatSource source = sources.getFirst();
      assertThat(source.getFileName()).isEqualTo("fabricated.pdf");
      assertThat(source.getDocumentId()).isNull();
      assertThat(source.getSourceType()).isNull();
      assertThat(source.getSourceUrl()).isNull();
    }

    /**
     * A citation whose document id is not among the retrieved chunks is flagged as invalid - never
     * silently dropped, and never allowed through as an ordinary, unflagged citation. Without a
     * synthetic entry nothing in the response could carry the flag.
     */
    @Test
    void marksCitationToAFabricatedDocumentIdAsInvalid() {
      List<ChatSource> sources =
          assembler.assemble(
              List.of(README),
              List.of(citation("fabricated-id", "fabricated-name.pdf", false)),
              MetadataFilter.NONE);

      assertThat(sources)
          .anySatisfy(
              source -> {
                assertThat(source.getFileName()).isEqualTo("fabricated-name.pdf");
                assertThat(source.getCitationValid()).isFalse();
                assertThat(source.getCited()).isTrue();
              });
    }

    /**
     * A fabricated citation naming a real, retrieved-but-uncited file folds into that real entry
     * instead of adding a second row: only {@code citationValid} moves to {@code false}, nothing
     * else about the real entry changes - it must not appear cited, and the frontend's file-name
     * join must not resolve to a synthetic zero-relevance row.
     */
    @Test
    void foldsACollidingSyntheticEntryIntoTheRealUncitedSourceInsteadOfAddingARow() {
      List<ChatSource> sources =
          assembler.assemble(
              List.of(README),
              List.of(citation("fabricated-id", "readme.md", false)),
              MetadataFilter.NONE);

      assertThat(sources).hasSize(1);
      ChatSource source = sources.getFirst();
      assertThat(source.getFileName()).isEqualTo("readme.md");
      assertThat(source.getRelevanceScore()).isEqualTo(1.0);
      assertThat(source.getMatchCount()).isEqualTo(1);
      assertThat(source.getCited()).isFalse();
      assertThat(source.getCitationValid()).isFalse();
    }

    /**
     * A source both validly cited and named by a colliding fabricated citation keeps its real
     * {@code cited = true}, relevance score and link; only {@code citationValid} reflects the
     * separate, invalid citation that also named this file.
     */
    @Test
    void preservesRealMetadataOnAValidlyCitedSourceThatAlsoCollidesWithASyntheticEntry() {
      List<ChatSource> sources =
          assembler.assemble(
              List.of(README),
              List.of(
                  citation("doc-123", "readme.md", true),
                  citation("fabricated-id", "readme.md", false)),
              MetadataFilter.NONE);

      assertThat(sources).hasSize(1);
      ChatSource source = sources.getFirst();
      assertThat(source.getFileName()).isEqualTo("readme.md");
      assertThat(source.getRelevanceScore()).isEqualTo(1.0);
      assertThat(source.getCited()).isTrue();
      assertThat(source.getCitationValid()).isFalse();
    }
  }

  /** How a source relates to the active metadata filter (ADR-0024, Leerwert rule). */
  @Nested
  class MetadataFilterMark {

    @Test
    void noMarkWithoutAnActiveFilterOrWithoutAResolvedDocument() {
      MetadataFilter filter = MetadataFilter.parse(List.of("SATZUNG"), null, null);

      assertThat(
              ChatSourceAssembler.metadataFilterMatch(
                  MetadataFilter.NONE, CoreMetadata.EMPTY, README))
          .isNull();
      assertThat(ChatSourceAssembler.metadataFilterMatch(filter, null, README)).isNull();
    }

    /**
     * Read from the chunk's own filtered keys: a chunk carrying the filtered field matched, a chunk
     * without a value for it was kept by the Leerwert rule alone.
     */
    @Test
    void marksAMatchedDocumentAndOneKeptWithoutValue() {
      UUID withTypeId = UUID.randomUUID();
      UUID withoutTypeId = UUID.randomUUID();
      var withType =
          Document.builder()
              .text("Satzung")
              .metadata(
                  Map.of(
                      "file_name",
                      "satzung.md",
                      "document_id",
                      withTypeId.toString(),
                      CoreMetadataChunkKeys.DOCUMENT_TYPE,
                      "SATZUNG"))
              .score(0.9)
              .build();
      var withoutType = chunk("ohne.md", withoutTypeId.toString(), "Ohne Dokumentart", 0.8);
      when(documentRepository.findById(withTypeId))
          .thenReturn(
              Optional.of(
                  new io.opaa.knowledge.Document(
                      "satzung.md", "/s.md", "text/markdown", 1L, SourceTypes.FILESYSTEM)));
      when(documentRepository.findById(withoutTypeId))
          .thenReturn(
              Optional.of(
                  new io.opaa.knowledge.Document(
                      "ohne.md", "/o.md", "text/markdown", 1L, SourceTypes.FILESYSTEM)));

      List<ChatSource> sources =
          assembler.assemble(
              List.of(withType, withoutType),
              List.of(),
              MetadataFilter.parse(List.of("SATZUNG"), null, null));

      assertThat(sources)
          .extracting(ChatSource::getFileName, ChatSource::getMetadataFilterMatch)
          .containsExactly(
              tuple("satzung.md", MetadataFilterMatch.MATCHED),
              tuple("ohne.md", MetadataFilterMatch.NO_VALUE));
    }
  }

  /**
   * A source of a library that is not updated carries the reason, who is in charge and the
   * library's newest successful run - not the document's own {@code indexedAt}. Only the libraries
   * behind the retrieved documents are looked up.
   */
  @Test
  void marksASourceOfAFrozenLibraryWithTheLibrarysLastRun() {
    KnowledgeLibrary lockedLibrary =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Alt", null, UUID.randomUUID());
    KnowledgeLibrary freeLibrary =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Neu", null, UUID.randomUUID());
    UUID lockedDocumentId = UUID.randomUUID();
    UUID freeDocumentId = UUID.randomUUID();
    Instant documentChanged = Instant.parse("2025-01-10T08:00:00Z");
    Instant lastRun = Instant.parse("2026-10-02T03:00:00Z");
    io.opaa.knowledge.Document locked =
        new io.opaa.knowledge.Document(
            "alt.md", "/alt.md", "text/markdown", 1L, SourceTypes.FILESYSTEM);
    locked.setLibraryId(lockedLibrary.getId());
    locked.setIndexedAt(documentChanged);
    io.opaa.knowledge.Document free =
        new io.opaa.knowledge.Document(
            "neu.md", "/neu.md", "text/markdown", 1L, SourceTypes.FILESYSTEM);
    free.setLibraryId(freeLibrary.getId());
    when(documentRepository.findById(lockedDocumentId)).thenReturn(Optional.of(locked));
    when(documentRepository.findById(freeDocumentId)).thenReturn(Optional.of(free));
    KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
    when(libraries.findAllById(Set.of(lockedLibrary.getId(), freeLibrary.getId())))
        .thenReturn(List.of(lockedLibrary, freeLibrary));
    List<Set<UUID>> askedFor = new ArrayList<>();
    SourceStateLookup states =
        asked -> {
          askedFor.add(asked.stream().map(KnowledgeLibrary::getId).collect(Collectors.toSet()));
          return asked.stream()
              .filter(library -> library.getId().equals(lockedLibrary.getId()))
              .collect(
                  Collectors.toMap(
                      KnowledgeLibrary::getId,
                      library ->
                          new SourceBlock(
                              SourceBlock.Reason.TYPE_LOCKED,
                              "Systemverwaltung",
                              "Gesperrt – Inhalt wird nicht mehr aktualisiert.")));
        };
    IndexingJobRepository jobs = mock(IndexingJobRepository.class);
    when(jobs.findLastCompletedByLibraryIdIn(Set.of(lockedLibrary.getId())))
        .thenReturn(
            List.of(
                new IndexingJobRepository.LibraryLastCompleted() {
                  @Override
                  public UUID getLibraryId() {
                    return lockedLibrary.getId();
                  }

                  @Override
                  public Instant getLastCompletedAt() {
                    return lastRun;
                  }
                }));
    ChatSourceAssembler withStates =
        new ChatSourceAssembler(
            documentRepository,
            documentMetadataService,
            mock(CitationMetadataReader.class),
            libraries,
            SourceConnectorStubs.registry(),
            states,
            jobs);

    List<ChatSource> sources =
        withStates.assemble(
            List.of(
                chunk("alt.md", lockedDocumentId.toString(), "alt", 0.9),
                chunk("neu.md", freeDocumentId.toString(), "neu", 0.8)),
            List.of(),
            MetadataFilter.NONE);

    assertThat(askedFor).containsExactly(Set.of(lockedLibrary.getId(), freeLibrary.getId()));
    assertThat(sources)
        .extracting(
            ChatSource::getFileName,
            ChatSource::getFreezeReason,
            ChatSource::getFreezeResponsible,
            ChatSource::getFreezeAsOf)
        .containsExactly(
            tuple("alt.md", "LOCKED", "Systemverwaltung", lastRun),
            tuple("neu.md", null, null, null));
    assertThat(sources.getFirst().getIndexedAt()).isEqualTo(documentChanged);
  }

  /**
   * A malformed (non-UUID) {@code document_id} in chunk metadata points at a data problem - corrupt
   * indexing, a botched migration or a version mismatch between indexer and query service - not a
   * transient failure, so both the document lookup and the id parsing log it at WARN, where it
   * survives a production log level, rather than at DEBUG where it is silently dropped.
   */
  @Test
  void logsInvalidDocumentIdAtWarnLevel() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(ChatSourceAssembler.class);
    var logAppender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logAppender.start();
    logger.addAppender(logAppender);
    try {
      assembler.assemble(
          List.of(chunk("broken.pdf", "not-a-uuid", "Corrupted metadata content", 0.8)),
          List.of(),
          MetadataFilter.NONE);

      var invalidDocumentIdEvents =
          logAppender.list.stream()
              .filter(event -> event.getFormattedMessage().contains("not-a-uuid"))
              .toList();
      assertThat(invalidDocumentIdEvents).isNotEmpty();
      assertThat(invalidDocumentIdEvents)
          .allSatisfy(
              event -> assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
    } finally {
      logger.detachAppender(logAppender);
    }
  }

  @Nested
  class MergeSourceReferences {

    private static final Instant INDEXED_AT = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void keepsHigherRelevanceScore() {
      var high = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);
      var low = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, false);

      var result = ChatSourceAssembler.mergeSourceReferences(high, low);

      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
    }

    /** #667: the merged entry knows every retrieved chunk's location, in chunk order. */
    @Test
    void unionsChunkLocationsInChunkOrder() {
      var high = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);
      high.setChunkLocations(List.of(new ChatSourceLocation(5).location("S. 3")));
      var low = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, true);
      low.setChunkLocations(List.of(new ChatSourceLocation(2).location("S. 1")));

      var result = ChatSourceAssembler.mergeSourceReferences(high, low);

      assertThat(result.getChunkLocations())
          .extracting(ChatSourceLocation::getChunkIndex, ChatSourceLocation::getLocation)
          .containsExactly(tuple(2, "S. 1"), tuple(5, "S. 3"));
    }

    @Test
    void keepsHigherScoreRegardlessOfOrder() {
      var low = sourceReference("file.pdf", 0.3, 1, INDEXED_AT, false);
      var high = sourceReference("file.pdf", 0.8, 1, INDEXED_AT, false);

      var result = ChatSourceAssembler.mergeSourceReferences(low, high);

      assertThat(result.getRelevanceScore()).isEqualTo(0.8);
    }

    @Test
    void prefersFirstWhenScoresAreEqual() {
      var first = sourceReference("file.pdf", 0.7, 2, INDEXED_AT, true);
      var second = sourceReference("file.pdf", 0.7, 1, INDEXED_AT, false);

      var result = ChatSourceAssembler.mergeSourceReferences(first, second);

      assertThat(result).isEqualTo(first);
    }

    @Test
    void preservesCitedWhenHigherScoreIsCited() {
      var cited = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, true);
      var uncited = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, false);

      var result = ChatSourceAssembler.mergeSourceReferences(cited, uncited);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
    }

    @Test
    void forcesCitedWhenLowerScoreIsCitedButHigherWins() {
      var citedLow = sourceReference("file.pdf", 0.3, 1, INDEXED_AT, true);
      var uncitedHigh = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);

      var result = ChatSourceAssembler.mergeSourceReferences(citedLow, uncitedHigh);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
      assertThat(result.getFileName()).isEqualTo("file.pdf");
    }

    @Test
    void returnsFalseWhenNeitherIsCited() {
      var a = sourceReference("file.pdf", 0.8, 1, INDEXED_AT, false);
      var b = sourceReference("file.pdf", 0.6, 1, INDEXED_AT, false);

      var result = ChatSourceAssembler.mergeSourceReferences(a, b);

      assertThat(result.getCited()).isFalse();
    }

    @Test
    void preservesMetadataFromPreferredSource() {
      var indexedEarly = Instant.parse("2024-01-01T00:00:00Z");
      var indexedLate = Instant.parse("2025-06-01T00:00:00Z");
      var high = sourceReference("report.pdf", 0.95, 3, indexedLate, false);
      var low = sourceReference("report.pdf", 0.4, 1, indexedEarly, true);

      var result = ChatSourceAssembler.mergeSourceReferences(high, low);

      assertThat(result.getMatchCount()).isEqualTo(3);
      assertThat(result.getIndexedAt()).isEqualTo(indexedLate);
      assertThat(result.getCited()).isTrue();
    }

    /**
     * #639: the branch that builds a fresh {@code ChatSource} to force {@code cited = true}
     * (because a lower-scoring duplicate was cited but the higher-scoring one is preferred) carries
     * {@code sourceEntryUrl} over from the preferred source, same as {@code indexedAt}.
     */
    @Test
    void preservesSourceEntryUrlWhenForcingCited() {
      var citedLow =
          sourceReference("report.pdf", 0.3, 1, INDEXED_AT, true, "https://example.com/entry-1");
      var uncitedHigh =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");

      var result = ChatSourceAssembler.mergeSourceReferences(citedLow, uncitedHigh);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getSourceEntryUrl()).isEqualTo("https://example.com/entry-1");
    }

    /**
     * #666: two distinct documents can share a file name, each with its own {@code sourceEntryUrl}
     * - picking either side's URL for the merged citation would be an unverifiable, potentially
     * wrong claim about where the other chunk actually came from. The merge drops to {@code null}
     * rather than assert one of two disagreeing URLs.
     */
    @Test
    void dropsSourceEntryUrlWhenMergedSourcesDisagree() {
      var a =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");
      var b =
          sourceReference("report.pdf", 0.5, 1, INDEXED_AT, false, "https://example.com/entry-2");

      var result = ChatSourceAssembler.mergeSourceReferences(a, b);

      assertThat(result.getSourceEntryUrl()).isNull();
    }

    /**
     * #666: one side carrying no {@code sourceEntryUrl} at all (not merely a different one) is also
     * a disagreement - a document with a URL and one without do not corroborate each other.
     */
    @Test
    void dropsSourceEntryUrlWhenOnlyOneSourceHasOne() {
      var withUrl =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");
      var withoutUrl = sourceReference("report.pdf", 0.5, 1, INDEXED_AT, false, null);

      var result = ChatSourceAssembler.mergeSourceReferences(withUrl, withoutUrl);

      assertThat(result.getSourceEntryUrl()).isNull();
    }
  }

  private static ChatSource sourceReference(
      String fileName, double relevanceScore, int matchCount, Instant indexedAt, boolean cited) {
    return sourceReference(fileName, relevanceScore, matchCount, indexedAt, cited, null);
  }

  private static ChatSource sourceReference(
      String fileName,
      double relevanceScore,
      int matchCount,
      Instant indexedAt,
      boolean cited,
      String sourceEntryUrl) {
    ChatSource sourceReference = new ChatSource(fileName, relevanceScore, matchCount, cited);
    sourceReference.setIndexedAt(indexedAt);
    sourceReference.setSourceEntryUrl(sourceEntryUrl);
    return sourceReference;
  }

  @Test
  void everyReasonShownInAnAnswerIsAFreezeReasonOfTheApi() {
    for (SourceBlock.Reason reason : SourceBlock.Reason.values()) {
      if (reason.shownInAnswer()) {
        assertThat(SourceFreezeReason.fromValue(ChatSourceAssembler.freezeReason(reason)))
            .isNotNull();
      }
    }
  }
}
