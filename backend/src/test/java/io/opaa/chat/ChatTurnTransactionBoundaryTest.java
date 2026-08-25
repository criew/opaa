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
 * concurrent-connections test, deliberately: the previous manual {@code TransactionTemplate}/{@code
 * Propagation.NOT_SUPPORTED} construct these annotations replace had no such test either, only this
 * same class of structural Javadoc contract.
 */
class ChatTurnTransactionBoundaryTest {

  /**
   * {@link ChatService#appendTurn} must carry no {@code @Transactional} annotation of its own: with
   * the class-level annotation removed (#889), a class-level default no longer needs an override,
   * and any annotation here would again wrap the whole method - including its LLM-call-adjacent
   * retry loop - in one ambient transaction, holding a connection the caller never needs.
   */
  @Test
  void appendTurnCarriesNoTransactionalAnnotation() throws NoSuchMethodException {
    Method appendTurn =
        ChatService.class.getMethod(
            "appendTurn", Chat.class, String.class, String.class, List.class);

    assertThat(appendTurn.getAnnotation(Transactional.class)).isNull();
  }

  /**
   * {@link ChatService} must carry no class-level {@code @Transactional} annotation at all (#889) -
   * every reading method declares its own explicit {@code @Transactional(readOnly = true)} instead,
   * precisely so {@link #appendTurnCarriesNoTransactionalAnnotation} holds without needing an
   * override.
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
