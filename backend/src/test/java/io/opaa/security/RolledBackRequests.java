package io.opaa.security;

import io.opaa.instance.InvalidationSubscriber;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends each request in a transaction of the test thread that is rolled back afterwards, after
 * {@code seed} wrote its rows there; then drops every cache on the invalidation bus. A write in a
 * transaction of its own escapes; one blocked on this transaction's locks ends at the timeouts.
 */
final class RolledBackRequests {

  /**
   * Operations that refuse a surrounding transaction and are sent without one or the seed; the
   * probe signs in with an address no account has, which writes nothing.
   */
  private static final Set<String> OUTSIDE_TRANSACTION = Set.of("localLogin");

  /** One answer: its status and body. */
  record Answer(int status, String body) {}

  private final MockMvc mockMvc;
  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;
  private final List<InvalidationSubscriber> caches;

  RolledBackRequests(
      MockMvc mockMvc,
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc,
      List<InvalidationSubscriber> caches) {
    this.mockMvc = mockMvc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.jdbc = jdbc;
    this.caches = caches;
  }

  /** The answer each operation gave, by operation id, in the order given. */
  Map<String, Answer> answers(
      List<ApiOperations.Operation> operations,
      Function<ApiOperations.Operation, AbstractMockHttpServletRequestBuilder<?>> requests,
      Consumer<JdbcTemplate> seed) {
    Map<String, Answer> answers = new LinkedHashMap<>();
    for (ApiOperations.Operation operation : operations) {
      if (OUTSIDE_TRANSACTION.contains(operation.id())) {
        answers.put(operation.id(), answer(requests.apply(operation)));
        caches.forEach(InvalidationSubscriber::invalidateAll);
        continue;
      }
      try {
        transactions.executeWithoutResult(
            transaction -> {
              // A REQUIRES_NEW write waiting on a row this transaction holds would wait for good:
              // the idle timeout ends this session and with it the wait.
              jdbc.execute("SET LOCAL lock_timeout = '10s'");
              jdbc.execute("SET LOCAL idle_in_transaction_session_timeout = '60s'");
              seed.accept(jdbc);
              answers.put(operation.id(), answer(requests.apply(operation)));
              transaction.setRollbackOnly();
            });
      } finally {
        caches.forEach(InvalidationSubscriber::invalidateAll);
      }
    }
    return answers;
  }

  private Answer answer(AbstractMockHttpServletRequestBuilder<?> request) {
    try {
      MvcResult result = mockMvc.perform(request).andReturn();
      return new Answer(
          result.getResponse().getStatus(),
          result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    } catch (Exception unhandled) {
      // An exception no handler maps reaches the servlet container as a 500.
      return new Answer(500, String.valueOf(unhandled));
    }
  }
}
