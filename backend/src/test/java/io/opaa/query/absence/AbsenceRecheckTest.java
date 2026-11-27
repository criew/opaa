package io.opaa.query.absence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.metadata.MetadataFilter;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.answer.ActiveAnswerStreams;
import io.opaa.query.answer.AnswerReaderGoneException;
import io.opaa.query.answer.AnswerStreamListener;
import io.opaa.query.citation.CitationParser;
import io.opaa.query.citation.CitationValidator;
import io.opaa.retrieval.KnowledgeRetrieval;
import io.opaa.retrieval.RetrievalExplanation;
import io.opaa.retrieval.RetrievalPipelineResult;
import io.opaa.retrieval.context.NeighborChunkExpansion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class AbsenceRecheckTest {

  private static final String QUESTION = "Wie hoch ist die Gebühr nach Tarifstelle AB-08?";
  private static final String CLAIM =
      "Die Gebühr ist in den vorliegenden Dokumenten nicht enthalten.";

  private final KnowledgeRetrieval retrieval = mock(KnowledgeRetrieval.class);
  private final NeighborChunkExpansion expansion = mock(NeighborChunkExpansion.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final QueryMetrics metrics = new QueryMetrics(meters);
  private final Set<UUID> scope = Set.of(UUID.randomUUID());
  private final MetadataFilter filter =
      new MetadataFilter(Set.of("BESCHEID"), null, null, List.of(), List.of());

  private final Document seen = passage("doc-1", 0, "Tarifstelle AB-08 steht in Anlage 2.");
  private final Document fresh = passage("doc-2", 3, "Tarifstelle AB-08: 12 Euro je Bescheid.");
  private final Document unrelated = passage("doc-3", 1, "Die Gebühr für Sondernutzungen.");

  @BeforeEach
  void setUp() {
    when(expansion.expand(any(), anySet())).thenAnswer(call -> List.of());
  }

  @Test
  void searchesTheKeyTermsWithinTheScopeAndFilterAndKeepsOnlyNewMatchingPassages() {
    when(retrieval.retrieveAsGiven(eq("AB-08 gebühr tarifstelle"), eq(scope), eq(filter)))
        .thenReturn(result(unrelated, seen, fresh));

    AbsenceRecheck.Outcome outcome =
        recheck(true).recheck(QueryMetrics.Mode.RAG, QUESTION, CLAIM, scope, filter, List.of(seen));

    assertThat(outcome.passages()).containsExactly(fresh);
    assertThat(outcome.searchQuery()).isEqualTo("AB-08 gebühr tarifstelle");
    assertThat(outcome.hits()).isEqualTo(3);
    assertThat(count("rag", "detected")).isEqualTo(1);
    assertThat(count("rag", "searched")).isEqualTo(1);
    assertThat(count("rag", "replaced")).isZero();
  }

  @Test
  void aSideRemarkInACitedAnswerIsNoClaimAboutTheQuestion() {
    String answer =
        "Tarifstelle AB-08 steht in Anlage 2 【source: doc-1#0 | doc-1.pdf】. Wann die Tabelle"
            + " zuletzt geändert wurde, ist in den Quellen nicht enthalten.";

    AbsenceRecheck.Outcome outcome =
        recheck(true)
            .recheck(QueryMetrics.Mode.RAG, QUESTION, answer, scope, filter, List.of(seen));

    assertThat(outcome.searched()).isFalse();
    verifyNoInteractions(retrieval);
    assertThat(count("rag", "detected")).isZero();
  }

  @Test
  void aSideRemarkCountsWhenTheAnswerCitesNothingValid() {
    when(retrieval.retrieveAsGiven(any(), any(), any())).thenReturn(result(fresh));
    String answer = "Wann die Tabelle zuletzt geändert wurde, ist in den Quellen nicht enthalten.";

    AbsenceRecheck.Outcome outcome =
        recheck(true)
            .recheck(QueryMetrics.Mode.RAG, QUESTION, answer, scope, filter, List.of(seen));

    assertThat(outcome.passages()).containsExactly(fresh);
  }

  @Test
  void aCitedAnswerWhoseClaimNamesTheQuestionIsRechecked() {
    when(retrieval.retrieveAsGiven(any(), any(), any())).thenReturn(result(fresh));
    String answer =
        "Anlage 2 nennt die Tarifstellen 【source: doc-1#0 | doc-1.pdf】. Die Gebühr der"
            + " Tarifstelle AB-08 ist in den Quellen nicht enthalten.";

    AbsenceRecheck.Outcome outcome =
        recheck(true)
            .recheck(QueryMetrics.Mode.RAG, QUESTION, answer, scope, filter, List.of(seen));

    assertThat(outcome.passages()).containsExactly(fresh);
  }

  @Test
  void aFailingSearchLeavesTheAnswerAsItIs() {
    when(retrieval.retrieveAsGiven(any(), any(), any()))
        .thenThrow(new IllegalStateException("search failed"));

    AbsenceRecheck.Outcome outcome =
        recheck(true).recheck(QueryMetrics.Mode.RAG, QUESTION, CLAIM, scope, filter, List.of());

    assertThat(outcome.searched()).isTrue();
    assertThat(outcome.passages()).isEmpty();
  }

  @Test
  void addsTheNeighboursOfTheNewPassagesOnce() {
    Document neighbour = passage("doc-2", 4, "Ermäßigungen siehe Anlage 3.");
    when(retrieval.retrieveAsGiven(any(), any(), any())).thenReturn(result(fresh));
    when(expansion.expand(List.of(fresh), scope)).thenReturn(List.of(fresh, neighbour, seen));

    AbsenceRecheck.Outcome outcome =
        recheck(true)
            .recheck(QueryMetrics.Mode.AGENT, QUESTION, CLAIM, scope, filter, List.of(seen));

    assertThat(outcome.passages()).containsExactly(fresh, neighbour);
  }

  @Test
  void takesAtMostTheConfiguredNumberOfPassages() {
    List<Document> hits = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      hits.add(passage("doc-" + (10 + i), 0, "Tarifstelle AB-08, Fall " + i));
    }
    when(retrieval.retrieveAsGiven(any(), any(), any()))
        .thenReturn(
            new RetrievalPipelineResult(
                hits, List.of(), new RetrievalExplanation(List.of()), true));

    assertThat(
            recheck(true)
                .recheck(QueryMetrics.Mode.RAG, QUESTION, CLAIM, scope, filter, List.of())
                .passages())
        .hasSize(4);
  }

  @Test
  void aShortAbbreviationAloneDoesNotSingleOutAPassage() {
    when(retrieval.retrieveAsGiven(any(), any(), any()))
        .thenReturn(result(passage("doc-4", 0, "Die DSGVO gilt für alle Verfahren.")));

    assertThat(
            recheck(true)
                .recheck(
                    QueryMetrics.Mode.RAG,
                    "Welche Löschfrist gilt nach der DSGVO für Bauakten?",
                    "Die Löschfrist ist in den Quellen nicht enthalten.",
                    scope,
                    filter,
                    List.of())
                .passages())
        .isEmpty();
  }

  @Test
  void anAnswerWithoutAClaimIsNotSearched() {
    AbsenceRecheck.Outcome outcome =
        recheck(true)
            .recheck(QueryMetrics.Mode.RAG, QUESTION, "Es sind 12 Euro.", scope, filter, List.of());

    assertThat(outcome.searched()).isFalse();
    verifyNoInteractions(retrieval);
    assertThat(count("rag", "detected")).isZero();
  }

  @Test
  void switchedOffNothingIsSearched() {
    assertThat(
            recheck(false)
                .recheck(QueryMetrics.Mode.RAG, QUESTION, CLAIM, scope, filter, List.of())
                .searched())
        .isFalse();
    verifyNoInteractions(retrieval);
  }

  @Test
  void anEmptyScopeOrAQuestionWithoutKeyTermsIsDetectedButNotSearched() {
    AbsenceRecheck recheck = recheck(true);

    recheck.recheck(QueryMetrics.Mode.RAG, QUESTION, CLAIM, Set.of(), filter, List.of());
    recheck.recheck(QueryMetrics.Mode.RAG, "Und was ist mit dem?", CLAIM, scope, filter, List.of());

    verifyNoInteractions(retrieval);
    assertThat(count("rag", "detected")).isEqualTo(2);
    assertThat(count("rag", "searched")).isZero();
  }

  @Test
  void countsTheReplacedAnswerAndHasNoSeriesWithoutASearch() {
    recheck(true).replaced(QueryMetrics.Mode.AGENT);
    recheck(true).replaced(QueryMetrics.Mode.NO_SEARCH);

    assertThat(count("agent", "replaced")).isEqualTo(1);
    assertThat(meters.find("opaa.query.absence_recheck").tag("mode", "no_search").counters())
        .isEmpty();
  }

  @Test
  void aReaderThatWentAwayAndAShutdownEndTheTurnAnyOtherFailureDoesNot() {
    assertThat(AbsenceRecheck.mustPropagate(new AnswerReaderGoneException(new IOException("gone"))))
        .isTrue();
    assertThat(AbsenceRecheck.mustPropagate(ActiveAnswerStreams.shuttingDown())).isTrue();
    assertThat(AbsenceRecheck.mustPropagate(new IllegalStateException("model failed"))).isFalse();
  }

  @Test
  void attributesTheNewDocumentsToTheSubQueriesWhoseTermsTheyName() {
    Map<String, Set<Integer>> attributed =
        AbsenceRecheck.attributed(
            Map.of("doc-1", Set.of(1)),
            List.of("Frist für Anträge", "Gebühr nach Tarifstelle AB-08"),
            List.of(fresh, passage("doc-5", 0, "Allgemeine Hinweise")));

    assertThat(attributed)
        .containsEntry("doc-1", Set.of(1))
        .containsEntry("doc-2", Set.of(2))
        .containsEntry("doc-5", Set.of(1, 2));
  }

  @Test
  void theReplacingListenerTurnsTheFirstTextIntoAReplacement() throws Exception {
    AnswerStreamListener reader = mock(AnswerStreamListener.class);
    AnswerStreamListener replacing = AbsenceRecheck.replacing(reader);

    replacing.provisional("Neu", false);
    replacing.provisional("er Text", false);

    verify(reader).provisional("Neu", true);
    verify(reader).provisional("er Text", false);
    assertThat(AbsenceRecheck.replacing(null)).isNull();
  }

  private AbsenceRecheck recheck(boolean enabled) {
    return new AbsenceRecheck(
        retrieval,
        expansion,
        new CitationParser(),
        new CitationValidator(),
        new AbsenceRecheckProperties(enabled, 4),
        metrics);
  }

  private double count(String mode, String step) {
    return meters
        .get("opaa.query.absence_recheck")
        .tag("mode", mode)
        .tag("step", step)
        .counter()
        .count();
  }

  private static RetrievalPipelineResult result(Document... chunks) {
    return new RetrievalPipelineResult(
        List.of(chunks), List.of(), new RetrievalExplanation(List.of()), true);
  }

  private static Document passage(String documentId, int chunkIndex, String text) {
    return Document.builder()
        .text(text)
        .metadata(
            Map.of(
                "file_name",
                documentId + ".pdf",
                "document_id",
                documentId,
                "chunk_index",
                chunkIndex))
        .build();
  }
}
