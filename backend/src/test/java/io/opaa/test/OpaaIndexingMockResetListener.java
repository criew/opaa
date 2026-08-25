package io.opaa.test;

import io.opaa.llm.ActiveChatModelResolver;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Resets the {@link ChatModel}/{@link ActiveChatModelResolver} mocks {@link
 * OpaaIndexingMockConfiguration} publishes before every test method, so stubbing (and captured
 * invocations) from one test class sharing an {@link OpaaIndexingIntegrationTest} context never
 * leaks into another. Runs before JUnit's own {@code @BeforeEach}, so a class's {@code @BeforeEach}
 * stubbing always applies to an already-reset mock.
 */
final class OpaaIndexingMockResetListener extends AbstractTestExecutionListener {

  @Override
  public void beforeTestMethod(TestContext testContext) {
    Mockito.reset(
        testContext.getApplicationContext().getBean(ChatModel.class),
        testContext.getApplicationContext().getBean(ActiveChatModelResolver.class));
  }
}
