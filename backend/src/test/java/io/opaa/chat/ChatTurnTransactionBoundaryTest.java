package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * #889: pins the transaction-boundary structure that keeps the chat write path from holding a
 * connection over {@code QueryService#query}'s LLM call - the #299/#525 pool-deadlock class of bug
 * (see {@link ChatService#appendTurn}'s and {@link ChatMessageWriter#writeTurnOnce}'s Javadoc for
 * the full reasoning). Mirrors {@code
 * QueryServiceTest#queryMethodCarriesNoTransactionalAnnotation}, the equivalent structural proof
 * for the read/LLM side of the same pipeline - a reflection check, not a genuine
 * concurrent-connections test, deliberately: the manual {@code TransactionTemplate} this class's
 * replacement, {@link ChatMessageWriter}, replaces had no such test either, only this same class of
 * structural Javadoc contract.
 */
class ChatTurnTransactionBoundaryTest {

  /**
   * {@link ChatService#appendTurn} must carry {@link Propagation#NOT_SUPPORTED} (#889): it no
   * longer needs to override a class-level default (that default is gone), but the annotation stays
   * as a structural guarantee that this method's retry loop never runs inside a caller's ambient
   * transaction - the #299/#525 two-connections deadlock - rather than depending on every future
   * caller never carrying one.
   */
  @Test
  void appendTurnSuspendsAnyAmbientTransaction() throws NoSuchMethodException {
    Method appendTurn =
        ChatService.class.getMethod(
            "appendTurn", Chat.class, String.class, String.class, List.class);

    Transactional annotation = appendTurn.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
  }

  /**
   * {@link ChatService} must carry no class-level {@code @Transactional} annotation at all (#889) -
   * every reading method declares its own explicit {@code @Transactional(readOnly = true)} instead.
   */
  @Test
  void chatServiceCarriesNoClassLevelTransactionalAnnotation() {
    assertThat(ChatService.class.getAnnotation(Transactional.class)).isNull();
  }

  /**
   * {@link ChatMessageWriter#writeTurnOnce} is the one place in the chat write path that actually
   * opens a transaction - isolated per retry attempt via {@link Propagation#REQUIRES_NEW} so a
   * failed attempt's rollback cannot poison a later one sharing the same physical transaction (see
   * that method's Javadoc), the invariant the manual {@code TransactionTemplate} it replaces
   * existed to guarantee.
   */
  @Test
  void writeTurnOnceRequiresANewTransactionPerAttempt() throws NoSuchMethodException {
    Method writeTurnOnce =
        ChatMessageWriter.class.getDeclaredMethod(
            "writeTurnOnce", UUID.class, String.class, String.class, String.class, String.class);

    Transactional annotation = writeTurnOnce.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }
}
