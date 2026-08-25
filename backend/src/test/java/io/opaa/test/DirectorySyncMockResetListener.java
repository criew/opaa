package io.opaa.test;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Resets the {@link FakeDirectoryClient} bean {@link DirectorySyncMockConfiguration} publishes
 * before every test method, mirroring {@link OpaaIndexingMockResetListener} - without it, a class
 * relies on every test method calling {@code respondWith()}/{@code failWith()} itself before
 * exercising the fake; a method that forgets would silently inherit whatever the previous test (in
 * this class or a sibling class sharing the same context) last configured. Runs before JUnit's own
 * {@code @BeforeEach}, so a class's own {@code @BeforeEach} stubbing still applies afterwards.
 *
 * <p>Not wired into a meta-annotation (unlike {@link OpaaIndexingMockResetListener}, which lives on
 * {@code @OpaaIndexingIntegrationTest}): {@link DirectorySyncMockConfiguration} is imported
 * directly onto {@code @OpaaIntegrationTest} classes rather than through a dedicated
 * meta-annotation, so each importing class declares {@code @TestExecutionListeners(listeners =
 * DirectorySyncMockResetListener.class, mergeMode = MERGE_WITH_DEFAULTS)} itself. This does not
 * affect the Spring context cache key - {@code TestExecutionListeners} is execution machinery, not
 * part of {@code MergedContextConfiguration} - so it does not reintroduce the per-class context
 * split this listener exists to guard against.
 */
public final class DirectorySyncMockResetListener extends AbstractTestExecutionListener {

  @Override
  public void beforeTestMethod(TestContext testContext) {
    testContext.getApplicationContext().getBean(FakeDirectoryClient.class).reset();
  }
}
