package io.opaa.security;

import io.opaa.instance.InvalidationSubscriber;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends each request through MockMvc inside a transaction of the test thread that is rolled back
 * once the response is there; the request's own transactions join it. Every request therefore meets
 * the fixture as committed, whatever an earlier one created, changed or deleted, and nothing a
 * request writes in its transaction stays behind in the shared database. After every request each
 * cache on the invalidation bus is dropped, since a rolled-back write announces nothing there and a
 * cache may have loaded what it wrote.
 *
 * <p>What escapes: a write in a transaction of its own ({@code REQUIRES_NEW}, {@code
 * NOT_SUPPORTED}) and work handed to another thread. The caller cleans up after those by the ids of
 * its fixture.
 */
final class RolledBackRequests {

  private final MockMvc mockMvc;
  private final TransactionTemplate transactions;
  private final List<InvalidationSubscriber> caches;

  RolledBackRequests(
      MockMvc mockMvc,
      PlatformTransactionManager transactionManager,
      List<InvalidationSubscriber> caches) {
    this.mockMvc = mockMvc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.caches = caches;
  }

  /** The status each operation answered, by operation id, in the order given. */
  Map<String, Integer> statuses(
      List<ApiOperations.Operation> operations,
      Function<ApiOperations.Operation, AbstractMockHttpServletRequestBuilder<?>> requests) {
    Map<String, Integer> statuses = new LinkedHashMap<>();
    for (ApiOperations.Operation operation : operations) {
      try {
        transactions.executeWithoutResult(
            transaction -> {
              statuses.put(operation.id(), status(requests.apply(operation)));
              transaction.setRollbackOnly();
            });
      } finally {
        caches.forEach(InvalidationSubscriber::invalidateAll);
      }
    }
    return statuses;
  }

  private int status(AbstractMockHttpServletRequestBuilder<?> request) {
    try {
      return mockMvc.perform(request).andReturn().getResponse().getStatus();
    } catch (Exception unhandled) {
      // An exception no handler maps reaches the servlet container as a 500.
      return 500;
    }
  }
}
