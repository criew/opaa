/**
 * The document template, an asset type on the shell: a type table, an entity extending {@code
 * io.opaa.asset.Asset}, an {@code io.opaa.asset.AssetTypeDefinition}, the DOCX file and the
 * placeholders read from it, and the installation switch "Vorlagen" that offers the whole type.
 *
 * <p><b>No rendering here.</b> The backend admits a file and reads its tags as text ({@link
 * io.opaa.template.TemplateTagScanner}); filling a template runs only in the sandbox, so a tag can
 * never execute in the backend process. {@code io.opaa.architecture.NoTemplateEngineTest} holds
 * that no template engine is on the class path of this package.
 *
 * <p><b>Dependencies.</b> Builds on {@code io.opaa.asset} and asks {@code io.opaa.permission} for
 * roles only, like {@code io.opaa.prompt}.
 */
package io.opaa.template;
