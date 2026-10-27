package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.connection.token.GrantRevocations;
import io.opaa.instance.PostgresInvalidationBus;
import io.opaa.msgraph.FakeGraphServer;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Restores the stateful beans of {@link OpaaTestBeans} and the shared {@link FakeGraphServer}
 * before every test method, so stubbing, scripted directory responses and arranged drives never
 * leak between the classes sharing one context. Runs before JUnit's own {@code @BeforeEach}, so a
 * class's own setup still applies afterwards.
 *
 * <p>The Mockito spies {@link OpaaIntegrationTest} declares need no entry here - Spring's own
 * {@code MockitoResetTestExecutionListener} resets every {@code @MockitoSpyBean}. Listeners are
 * execution machinery and not part of {@code MergedContextConfiguration}, so this adds no context
 * of its own.
 *
 * <p>It also waits until every OAuth revocation an earlier method handed over has run, so none
 * reaches the shared fake authorization server after the next method reset it, and until every
 * announcement of the invalidation bus an earlier method caused has reached the caches, so none
 * drops a cache in the middle of the next method. Every scheduler lock an earlier method left is
 * freed, so a locked {@code @Scheduled} method a test calls runs regardless of the class before.
 */
final class OpaaTestBeanResetListener extends AbstractTestExecutionListener {

  private static final Duration REVOCATIONS_IDLE_TIMEOUT = Duration.ofSeconds(60);

  private static final Duration BUS_DELIVERY_TIMEOUT = Duration.ofSeconds(10);

  @Override
  public void beforeTestMethod(TestContext testContext) {
    testContext
        .getApplicationContext()
        .getBean(PostgresInvalidationBus.class)
        .awaitDelivery(BUS_DELIVERY_TIMEOUT);
    Mockito.reset(testContext.getApplicationContext().getBean(ChatModel.class));
    testContext.getApplicationContext().getBean(FakeDirectoryClient.class).reset();
    testContext
        .getApplicationContext()
        .getBeanProvider(FakeEmbeddingProbe.class)
        .ifAvailable(FakeEmbeddingProbe::reset);
    FakeGraphServer.resetShared();
    if (testContext.getApplicationContext().getBean(EmbeddingModel.class)
        instanceof FakeEmbeddingModel embeddingModel) {
      embeddingModel.reset();
    }
    testContext.getApplicationContext().getBean(TestAssetType.class).switchOn();
    ScheduledRunLockRelease.releaseAll(
        testContext.getApplicationContext().getBean(JdbcTemplate.class));
    GrantRevocations revocations =
        testContext.getApplicationContext().getBean(GrantRevocations.class);
    if (!revocations.idle()) {
      Awaitility.await().atMost(REVOCATIONS_IDLE_TIMEOUT).until(revocations::idle);
    }
  }
}
