package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.auth.UserRepository;
import io.opaa.group.sync.DirectoryClient;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.permission.AssetOwnershipHistoryRepository;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.space.SpaceMembershipHistoryRepository;
import io.opaa.space.SpaceRepository;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The three production collaborators no test of this suite may reach for real: the embedding
 * endpoint (unreachable in CI), a directory server, and a chat endpoint. Imported once by {@link
 * OpaaIntegrationTest} rather than declared per class, which is what keeps every class's merged
 * configuration - and therefore the Spring context cache key - identical.
 *
 * <p>{@code @Primary} is what displaces the autoconfigured beans; {@link OpaaTestBeanResetListener}
 * restores the two stateful ones ({@link ChatModel}, {@link FakeDirectoryClient}) before every test
 * method so nothing leaks between the classes sharing this context.
 */
@TestConfiguration
class OpaaTestBeans {

  @Bean
  @Primary
  EmbeddingModel testEmbeddingModel() {
    return new FakeEmbeddingModel();
  }

  /**
   * Additive rather than a replacement: {@code application.yml} excludes {@code
   * OpenAiChatAutoConfiguration}, so the application itself publishes no {@link ChatModel} bean at
   * all and nothing in {@code io.opaa} injects one.
   */
  @Bean
  @Primary
  ChatModel testChatModel() {
    return Mockito.mock(ChatModel.class);
  }

  /**
   * Declared as {@link FakeDirectoryClient}, not {@link DirectoryClient}: a test injects the
   * scriptable type to call {@code respondWith}/{@code failWith} on it.
   */
  @Bean
  @Primary
  FakeDirectoryClient testDirectoryClient() {
    return new FakeDirectoryClient();
  }

  /**
   * The test-defined asset type: registered in every context so a test can serve it through the one
   * grant service, derivation and association without a context of its own.
   */
  @Bean
  AssetTypeDefinition testAssetType() {
    return new TestAssetType();
  }

  @Bean
  LlmModelCatalogFixtures llmModelCatalogFixtures(JdbcTemplate jdbcTemplate) {
    return new LlmModelCatalogFixtures(jdbcTemplate);
  }

  @Bean
  OwnLibraryFixtures ownLibraryFixtures(
      JdbcTemplate jdbcTemplate,
      VectorChunkStore vectorChunkStore,
      @Qualifier("uploadTaskExecutor") TaskExecutor uploadTaskExecutor) {
    return new OwnLibraryFixtures(
        jdbcTemplate, vectorChunkStore, (ThreadPoolTaskExecutor) uploadTaskExecutor);
  }

  @Bean
  OwnOrganizationFixtures ownOrganizationFixtures(JdbcTemplate jdbcTemplate) {
    return new OwnOrganizationFixtures(jdbcTemplate);
  }

  @Bean
  OwnUserFixtures ownUserFixtures(
      UserRepository users,
      SpaceRepository spaces,
      AssetGrantHistoryRepository grantHistory,
      GroupMembershipHistoryRepository membershipHistory,
      SpaceMembershipHistoryRepository spaceMembershipHistory,
      AssetOwnershipHistoryRepository ownershipHistory) {
    return new OwnUserFixtures(
        users, spaces, grantHistory, membershipHistory, spaceMembershipHistory, ownershipHistory);
  }
}
