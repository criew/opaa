package io.opaa.query;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CitationParser {

  static final Pattern CITATION_PATTERN =
      Pattern.compile("【source:\\s*([a-zA-Z0-9\\-]+)#(\\d+)\\s*\\|\\s*(.+?)】");

  public Set<String> extractCitedDocumentIds(String answer) {
    Set<String> documentIds = new LinkedHashSet<>();
    if (answer == null || answer.isEmpty()) {
      return documentIds;
    }
    Matcher matcher = CITATION_PATTERN.matcher(answer);
    while (matcher.find()) {
      documentIds.add(matcher.group(1).trim());
    }
    return documentIds;
  }
}
