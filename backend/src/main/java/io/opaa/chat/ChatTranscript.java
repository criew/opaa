package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chat as an export reads it: title, then every turn in order with its text and, for an answer,
 * the sources it cites. Each well-formed citation marker becomes a footnote {@code [n]} numbered
 * like the chat numbers it (first appearance per {@code documentId#chunk}); malformed markers are
 * dropped, never shown raw. Cited sources without a marker in the text follow without a number. The
 * Gesprächsnotiz comes along as its points' texts, oldest first.
 *
 * <p>Every pattern here scans a bounded stretch per match attempt (a marker stops at the next
 * opening bracket or line end), so preparing a transcript stays linear in the text.
 */
record ChatTranscript(
    String title, Instant createdAt, List<String> notePoints, List<Entry> entries) {

  static final String UNTITLED = "Unbenannter Chat";

  /** Bracket levels unwrapped around markers; deeper nesting keeps its outer brackets. */
  private static final int MAX_BRACKET_LEVELS = 8;

  private static final String MARKER_LIST =
      "[ \\t]*(?:"
          + CitationMarker.CLAIMED_PATTERN.pattern()
          + ")(?:[ \\t]*(?:[,;][ \\t]*)?"
          + CitationMarker.CLAIMED_PATTERN.pattern()
          + ")*[ \\t]*";

  /** Brackets holding nothing but markers; {@code [..](} is a Markdown link and stays. */
  private static final Pattern BRACKETED_MARKERS =
      Pattern.compile("\\(" + MARKER_LIST + "\\)|\\[" + MARKER_LIST + "\\](?!\\()");

  ChatTranscript {
    notePoints = List.copyOf(notePoints);
    entries = List.copyOf(entries);
  }

  /**
   * @param text the turn's text, Markdown as stored, with markers replaced by footnotes
   * @param sources one line per cited source: {@code [n] fileName, location} for the footnotes,
   *     then the cited sources the text never marks, without a number
   */
  record Entry(ChatRole role, Instant createdAt, String text, List<String> sources) {
    Entry {
      sources = List.copyOf(sources);
    }
  }

  static ChatTranscript of(ChatConversation chat) {
    String title =
        chat.getTitle() == null || chat.getTitle().isBlank() ? UNTITLED : chat.getTitle();
    List<Entry> entries = new ArrayList<>();
    for (ChatTurn turn : chat.getMessages()) {
      entries.add(
          turn.getRole() == ChatRole.ASSISTANT
              ? answer(turn)
              : new Entry(turn.getRole(), turn.getCreatedAt(), safe(turn.getContent()), List.of()));
    }
    List<String> notePoints =
        chat.getNoteItems().stream().map(point -> safe(point.text()).strip()).toList();
    return new ChatTranscript(title.strip(), chat.getCreatedAt(), notePoints, entries);
  }

  private static Entry answer(ChatTurn turn) {
    List<ChatSource> sources = turn.getSources() == null ? List.of() : turn.getSources();
    String content = unwrapBracketed(safe(turn.getContent()));
    Map<String, Integer> numberByKey = new LinkedHashMap<>();
    Map<Integer, String> noteByNumber = new LinkedHashMap<>();
    Set<String> referencedSources = new HashSet<>();
    StringBuilder body = new StringBuilder();
    Set<Integer> adjacent = new HashSet<>();
    int lastEnd = 0;
    Matcher matcher = CitationMarker.CLAIMED_PATTERN.matcher(content);
    while (matcher.find()) {
      String between = content.substring(lastEnd, matcher.start());
      if (!between.chars().allMatch(c -> c == ' ' || c == '\t')) {
        adjacent = new HashSet<>();
      }
      body.append(between);
      // The footnote attaches to the word: the spaces before the marker go with it.
      int end = body.length();
      while (end > 0 && (body.charAt(end - 1) == ' ' || body.charAt(end - 1) == '\t')) {
        end--;
      }
      body.setLength(end);
      lastEnd = matcher.end();
      List<CitationMarker> parsed = CitationMarker.parse(matcher.group());
      if (parsed.size() != 1 || !CitationMarker.isWellFormed(matcher.group())) {
        continue;
      }
      CitationMarker marker = parsed.getFirst();
      String key = marker.documentId() + "#" + marker.chunkIndex();
      Integer number = numberByKey.get(key);
      if (number == null) {
        number = numberByKey.size() + 1;
        numberByKey.put(key, number);
        ChatSource source = resolve(sources, marker);
        if (source != null) {
          referencedSources.add(sourceKey(source));
        }
        noteByNumber.put(number, "[" + number + "] " + note(marker, source));
      }
      if (adjacent.add(number)) {
        body.append('[').append(number).append(']');
      }
    }
    body.append(content.substring(lastEnd));

    List<String> lines = new ArrayList<>(noteByNumber.values());
    for (ChatSource source : sources) {
      if (source.getCited() && referencedSources.add(sourceKey(source))) {
        lines.add(safe(source.getFileName()));
      }
    }
    return new Entry(turn.getRole(), turn.getCreatedAt(), body.toString().strip(), lines);
  }

  private static ChatSource resolve(List<ChatSource> sources, CitationMarker marker) {
    for (ChatSource source : sources) {
      if (source.getDocumentId() != null
          && source.getDocumentId().toString().equals(marker.documentId())) {
        return source;
      }
    }
    for (ChatSource source : sources) {
      if (Objects.equals(source.getFileName(), marker.fileName())) {
        return source;
      }
    }
    return null;
  }

  private static String note(CitationMarker marker, ChatSource source) {
    String fileName = source != null ? safe(source.getFileName()) : marker.fileName();
    if (source == null || source.getChunkLocations() == null) {
      return fileName;
    }
    return source.getChunkLocations().stream()
        .filter(location -> location.getChunkIndex() == marker.chunkIndex())
        .map(ChatSourceLocation::getLocation)
        .filter(location -> location != null && !location.isBlank())
        .findFirst()
        .map(location -> fileName + ", " + location)
        .orElse(fileName);
  }

  private static String sourceKey(ChatSource source) {
    return source.getDocumentId() != null
        ? source.getDocumentId().toString()
        : "file:" + source.getFileName();
  }

  /**
   * {@code (【…】)} becomes {@code 【…】}, so the footnote shows no bracket of its own. Each pass drops
   * one level and costs one scan of the text, hence the bound on the passes.
   */
  private static String unwrapBracketed(String text) {
    String result = text;
    String previous;
    int passes = 0;
    do {
      previous = result;
      Matcher matcher = BRACKETED_MARKERS.matcher(previous);
      StringBuilder unwrapped = new StringBuilder(previous.length());
      while (matcher.find()) {
        String markers =
            CitationMarker.CLAIMED_PATTERN
                .matcher(matcher.group())
                .results()
                .map(MatchResult::group)
                .reduce("", String::concat);
        matcher.appendReplacement(unwrapped, Matcher.quoteReplacement(markers));
      }
      result = matcher.appendTail(unwrapped).toString();
    } while (!result.equals(previous) && ++passes < MAX_BRACKET_LEVELS);
    return result;
  }

  private static String safe(String text) {
    return text == null ? "" : text;
  }
}
