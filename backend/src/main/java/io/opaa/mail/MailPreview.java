package io.opaa.mail;

import java.util.Map;

/**
 * A preview render together with the sample values it was rendered with (#1536), so the editor can
 * show what produced the result and let it be changed without guessing the variable names.
 */
public record MailPreview(RenderedMail rendered, Map<String, String> variables) {}
