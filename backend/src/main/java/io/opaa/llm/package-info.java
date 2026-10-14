/**
 * Managed chat models with exactly one systemwide active entry. {@link io.opaa.llm.LlmModelService}
 * is the single entry point for reading and changing them; it encrypts the optional API key before
 * the database sees it and audits every change. {@link io.opaa.llm.LlmModelSeeder} takes over the
 * environment configuration once as the initial model.
 */
package io.opaa.llm;
