package io.opaa.test;

import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Restores the stateful beans of {@link OpaaTestBeans} before every test method, so stubbing and
 * scripted directory responses never leak between the classes sharing one context. Runs before
 * JUnit's own {@code @BeforeEach}, so a class's own setup still applies afterwards.
 *
 * <p>The Mockito spies {@link OpaaIntegrationTest} declares need no entry here - Spring's own
 * {@code MockitoResetTestExecutionListener} resets every {@code @MockitoSpyBean}. Listeners are
 * execution machinery and not part of {@code MergedContextConfiguration}, so this adds no context
 * of its own.
 */
final class OpaaTestBeanResetListener extends AbstractTestExecutionListener {

  @Override
  public void beforeTestMethod(TestContext testContext) {
    Mockito.reset(testContext.getApplicationContext().getBean(ChatModel.class));
    testContext.getApplicationContext().getBean(FakeDirectoryClient.class).reset();
  }
}
