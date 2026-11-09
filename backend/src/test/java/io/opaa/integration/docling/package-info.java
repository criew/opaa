/**
 * Integration tests of the text recognition client against a <em>real</em> docling-serve started in
 * Docker - the acceptance level above the stub in {@code
 * io.opaa.llm.textrecognition.TextRecognitionClientTest}, which stays the contract level for both
 * connection types.
 *
 * <p><b>Runs only via {@code ./gradlew doclingIntegrationTest}</b>, never as part of {@code
 * build}/{@code test}: the CPU image of docling-serve is several gigabytes and reading the test
 * page takes seconds of CPU per run. Docker is the only precondition, and the tests skip without
 * it. {@code OPAA_OCR_IT_IMAGE} replaces the image, e.g. by the shipped {@code opaa-ocr}. CI runs
 * the suite nightly and on demand ({@code .github/workflows/docling-integration.yml}).
 */
package io.opaa.integration.docling;
