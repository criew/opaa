package io.opaa.query.retrieval.search;

import io.opaa.query.ConversationNoteBlock;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalNote;
import io.opaa.query.retrieval.RetrievalStage;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.query.retrieval.StageOutcome;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#SUB_QUERY_DECOMPOSITION} stage: produces the search queries the
 * search stages run, one search call each.
 *
 * <p>{@link QueryDecompositionService#decompose} returns 1 to {@link QueryProperties#maxSubQueries}
 * self-contained queries, or an empty list on any failure, which falls back to the single query
 * {@link #buildSearchQuery} builds. {@link QueryProperties#queryDecompositionEnabled} {@code =
 * false} skips the LLM round trip and takes that same fallback. Touches no candidates: at this
 * point in the run there are none.
 *
 * <p>The chat's Gesprächsnotiz reaches the model here too, as a rendered context block of the
 * {@link DecompositionContext} - see {@link #decompositionContext}.
 *
 * <p><b>The search window is cut here, not before the run.</b> A {@link RetrievalContext} carries
 * the whole conversation window; this stage narrows it to {@link QueryProperties#searchWindowTurns}
 * turns for the decomposition and for the fallback (docs/features/conversation-memory.md, "Bauteil
 * 1"). Cutting it in the caller instead would leave every other entry point into the pipeline - the
 * administration's diagnosis, the evaluation harness - searching on a window production never uses.
 */
@Component
public class SubQueryDecompositionStage implements RetrievalStage {

  private static final Logger log = LoggerFactory.getLogger(SubQueryDecompositionStage.class);

  /** A turn is a question and its answer - the unit the search window is measured in. */
  private static final int MESSAGES_PER_TURN = 2;

  private final QueryDecompositionService queryDecompositionService;

  public SubQueryDecompositionStage(QueryDecompositionService queryDecompositionService) {
    this.queryDecompositionService = queryDecompositionService;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.SUB_QUERY_DECOMPOSITION;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    QueryProperties properties = context.queryProperties();
    List<Message> searchWindow =
        searchWindow(context.conversationHistory(), properties.searchWindowTurns());
    List<String> subQueries =
        properties.queryDecompositionEnabled()
            ? queryDecompositionService.decompose(
                decompositionContext(context, searchWindow), properties.maxSubQueries())
            : List.of();
    boolean decomposed = !subQueries.isEmpty();
    List<String> searchQueries =
        decomposed ? subQueries : List.of(buildSearchQuery(context.question(), searchWindow));

    List<String> notes = new ArrayList<>();
    if (decomposed) {
      notes.add(
          RetrievalNote.DECOMPOSITION_PRODUCED.format(
              searchQueries.size(), searchQueries.size() == 1 ? "sub-query" : "sub-queries"));
    } else if (properties.queryDecompositionEnabled()) {
      notes.add(RetrievalNote.DECOMPOSITION_FAILED.format());
    } else {
      notes.add(RetrievalNote.DECOMPOSITION_DISABLED.format());
    }
    searchQueries.forEach(query -> notes.add(RetrievalNote.SEARCH_QUERY.format(query)));

    return new StageOutcome(
        state.withSearchQueries(searchQueries),
        StageExplanation.executed(name(), 0, 0, List.of(), notes));
  }

  /**
   * The decomposition's context: question and search window, plus the rendered Gesprächsnotiz block
   * when the chat has {@code RAHMEN} points (#1487).
   *
   * <p><b>Through {@link DecompositionContext#withContextBlock}, never through the instruction
   * argument of {@link DecompositionContext#systemText}.</b> Only the former puts the block into
   * {@link DecompositionContext#contextTexts()} as well, and the safety belt of {@link
   * QueryDecompositionService} anchors against exactly those texts. A note rendered into the
   * instruction instead would reach the model while leaving {@code countUnrelated} blind to it -
   * and because that belt is all or nothing, an enriched sub-query the note legitimizes
   * ("Bezugsjahr 2024" -&gt; "Anwohnerparkausweis Gebühren 2024") would take the whole run into the
   * fallback, precisely in the {@code constraint_carryover} cases the note exists for.
   */
  private static DecompositionContext decompositionContext(
      RetrievalContext context, List<Message> searchWindow) {
    DecompositionContext decompositionContext =
        DecompositionContext.of(context.question(), searchWindow);
    String noteBlock = ConversationNoteBlock.render(context.conversationNote());
    return noteBlock == null
        ? decompositionContext
        : decompositionContext.withContextBlock(noteBlock);
  }

  /**
   * The most recent {@code turns} turns of {@code conversationWindow}, a turn being a question and
   * its answer. {@code turns = 0} yields an empty window: the decomposition then sees the question
   * alone.
   */
  static List<Message> searchWindow(List<Message> conversationWindow, int turns) {
    int messages = turns * MESSAGES_PER_TURN;
    if (messages <= 0) {
      return List.of();
    }
    return conversationWindow.size() <= messages
        ? conversationWindow
        : List.copyOf(
            conversationWindow.subList(
                conversationWindow.size() - messages, conversationWindow.size()));
  }

  /**
   * The fallback search query: the plain {@code question}, or - when the search window holds a
   * preceding turn - the <b>last</b> user message of that window prepended to it. The last one, not
   * the first: after a topic change the oldest question in the window is the one the current
   * question is least likely to continue.
   */
  static String buildSearchQuery(String question, List<Message> searchWindow) {
    String lastUserMessage = null;
    for (Message message : searchWindow) {
      if (message.getMessageType() == MessageType.USER) {
        lastUserMessage = message.getText();
      }
    }

    if (lastUserMessage == null) {
      return question;
    }

    log.debug(
        "Enriching search query with conversation context: '{}' -> '{} {}'",
        question,
        lastUserMessage,
        question);
    return lastUserMessage + " " + question;
  }
}
