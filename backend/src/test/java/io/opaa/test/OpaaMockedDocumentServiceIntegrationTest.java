package io.opaa.test;

import io.opaa.indexing.document.DocumentService;
import io.opaa.llm.ActiveChatModelResolver;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link OpaaIntegrationTest} with {@link DocumentService} and {@link ActiveChatModelResolver}
 * replaced by mocks, and the chat-title executor left asynchronous as in production.
 *
 * <p><b>Why {@link DocumentService} cannot be mocked canonically:</b> two classes need {@code
 * parseDocument} to return a scripted result (an unparseable document, a race window), which is not
 * neutral for the two dozen classes that index real fixtures. A spy is no way out either - both
 * stub with {@code when(documentService.parseDocument(file))}, which on a spy would run the real
 * parser while the stub is being built.
 *
 * <p><b>Why this is not {@link OpaaMockedChatModelIntegrationTest}:</b> that signature runs the
 * chat-title job inline, and one class here exercises exactly the opposite - a title generation
 * still in flight while the chat is renamed.
 *
 * <p><b>Rider:</b> {@link InventedDocumentFormat} is registered here too. It has nothing to do with
 * either mock - it rides along because it must stay out of the canonical context (see its own
 * Javadoc) and a signature of its own would cost a fifth Spring context and a fifth Postgres.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaIntegrationTest
@MockitoBean(types = {DocumentService.class, ActiveChatModelResolver.class})
@Import(InventedDocumentFormat.Registration.class)
public @interface OpaaMockedDocumentServiceIntegrationTest {}
