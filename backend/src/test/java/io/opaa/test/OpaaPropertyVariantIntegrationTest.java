package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OpaaIntegrationTest} with the two property sets that are themselves the subject under
 * test: an embedding base URL that differs from the chat one, and a pinned pgvector dimension with
 * schema initialisation switched off.
 *
 * <p><b>Why these cannot be canonical:</b> {@code ProviderConfigurationTest} asserts the canonical
 * signature's embedding base URL, so the override below must not reach it; and the classes of the
 * embedding index drop and recreate {@code vector_store} with a deliberately wrong dimension, which
 * no other class may see in its database - after the migration at the context's start nothing puts
 * the table back.
 *
 * <p>The external-access feature rides along switched off (ADR-0044): whether {@code /mcp} and the
 * channel's routes are absent can only be seen in a context started without them, and the classes
 * above never touch the channel ({@code ExternalAccessSwitchedOffIntegrationTest}).
 *
 * <p>The property sets do not contradict each other, so all classes share one context rather than
 * one each.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaIntegrationTest
@TestPropertySource(
    properties = {
      "spring.ai.openai.embedding.base-url=http://model-server.invalid:8000/v1",
      "spring.ai.vectorstore.pgvector.dimensions=1536",
      "spring.ai.vectorstore.pgvector.initialize-schema=false",
      "opaa.features.external-access.enabled=false"
    })
public @interface OpaaPropertyVariantIntegrationTest {}
