package io.opaa.test;

import io.opaa.llm.ActiveChatModelResolver;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link OpaaIntegrationTest} with {@link ActiveChatModelResolver} replaced by a mock, for the
 * classes that script a chat answer.
 *
 * <p><b>Why this cannot be the canonical signature:</b> {@code
 * ActiveChatModelResolverIntegrationTest} exercises the real resolver, and {@code
 * QueryControllerLlmErrorMappingIntegrationTest} needs the real one inside the production wiring
 * ({@code AnswerGenerationService} resolves it per request) to prove the HTTP error mapping of an
 * unreachable model. A {@code @Primary} mock would silently disable both.
 *
 * <p>{@link SynchronousChatTitleExecutorConfiguration} belongs to the same reason: a class here
 * re-stubs the shared {@code ChatModel} mock mid-test, which must not race an asynchronous
 * chat-title call on it (#616). A class that needs the production timing instead uses {@link
 * OpaaMockedDocumentServiceIntegrationTest}.
 *
 * <p><b>Rider:</b> {@link OpaaS3UploadStoreInitializer} points the upload store at MinIO here too.
 * That has nothing to do with the chat model - it rides along because a signature of its own would
 * cost a fifth Spring context and a fifth Postgres, and none of the classes below ever uploads a
 * document (AGENTS.md: a shared signature must be correct, not thematically coherent).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaIntegrationTest
@MockitoBean(types = ActiveChatModelResolver.class)
@Import(SynchronousChatTitleExecutorConfiguration.class)
// Repeats the two initializers of the canonical signature: @ContextConfiguration is resolved by
// nearest declaration, not merged - an initializer added to @OpaaIntegrationTest alone would
// never reach the classes below.
@ContextConfiguration(
    initializers = {
      OpaaTestPathInitializer.class,
      OpaaTestTargetAllowlistInitializer.class,
      OpaaS3UploadStoreInitializer.class
    })
public @interface OpaaMockedChatModelIntegrationTest {}
