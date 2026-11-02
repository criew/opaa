package io.opaa.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;

/**
 * Turns the ready attachments of a chat into the passages of one answer. Up to {@code
 * inlineMaxChars} characters in all, every chunk goes in, in document order - the full text, so
 * "fasse zusammen" works. Above it, the chunks that share the most terms with the question go in up
 * to {@code excerptMaxChars}, again in document order; a question without a usable term gets the
 * opening chunks of each attachment in turn. Lexical on purpose: nothing is embedded, so a fresh
 * attachment is usable the moment its text is read.
 *
 * <p>Each passage carries the attachment's id as {@code document_id}, so citation marker, validator
 * and source rows treat it like any retrieved chunk.
 */
final class ChatAttachmentPassages {

  /** Marks a passage as a chat attachment's in its metadata. */
  static final String ATTACHMENT_METADATA_KEY = "chat_attachment";

  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  private static final int MIN_TERM_LENGTH = 3;

  private static final Set<String> STOP_WORDS =
      Set.of(
          "der",
          "die",
          "das",
          "den",
          "dem",
          "des",
          "ein",
          "eine",
          "einen",
          "einem",
          "einer",
          "und",
          "oder",
          "aber",
          "mit",
          "von",
          "für",
          "auf",
          "aus",
          "bei",
          "nach",
          "über",
          "unter",
          "zum",
          "zur",
          "ist",
          "sind",
          "war",
          "wird",
          "werden",
          "wie",
          "was",
          "wer",
          "wo",
          "wann",
          "warum",
          "welche",
          "welcher",
          "welches",
          "steht",
          "stehen",
          "gibt",
          "dazu",
          "dies",
          "diese",
          "dieser",
          "dieses",
          "im",
          "in",
          "an",
          "am",
          "es",
          "sie",
          "er",
          "ich",
          "mir",
          "mich",
          "uns",
          "kann",
          "können",
          "soll",
          "sollen",
          "muss",
          "müssen",
          "nicht",
          "kein",
          "keine",
          "auch",
          "noch",
          "nur",
          "bitte",
          "mal",
          "doch",
          "the",
          "and",
          "for",
          "what",
          "with",
          "this",
          "that",
          "dokument",
          "dokuments",
          "datei",
          "anhang",
          "text");

  private ChatAttachmentPassages() {}

  /** The passages of one answer and whether they are excerpts rather than the full text. */
  record Selection(List<Document> passages, boolean excerpts) {
    static final Selection NONE = new Selection(List.of(), false);
  }

  static Selection select(
      List<ChatAttachment> attachments,
      Map<UUID, List<ChatAttachmentChunkStore.Chunk>> chunksByAttachment,
      String question,
      int inlineMaxChars,
      int excerptMaxChars) {
    Map<UUID, ChatAttachment> byId = new LinkedHashMap<>();
    List<ChatAttachmentChunkStore.Chunk> all = new ArrayList<>();
    for (ChatAttachment attachment : attachments) {
      byId.put(attachment.getId(), attachment);
      all.addAll(chunksByAttachment.getOrDefault(attachment.getId(), List.of()));
    }
    if (all.isEmpty()) {
      return Selection.NONE;
    }
    long total = all.stream().mapToLong(chunk -> chunk.text().length()).sum();
    if (total <= inlineMaxChars) {
      return new Selection(all.stream().map(chunk -> passage(chunk, byId)).toList(), false);
    }
    List<ChatAttachmentChunkStore.Chunk> chosen = excerpts(all, question, excerptMaxChars);
    Map<UUID, Integer> order = new HashMap<>();
    byId.keySet().forEach(id -> order.put(id, order.size()));
    chosen.sort(
        Comparator.comparing((ChatAttachmentChunkStore.Chunk c) -> order.get(c.attachmentId()))
            .thenComparingInt(ChatAttachmentChunkStore.Chunk::index));
    return new Selection(chosen.stream().map(chunk -> passage(chunk, byId)).toList(), true);
  }

  private static List<ChatAttachmentChunkStore.Chunk> excerpts(
      List<ChatAttachmentChunkStore.Chunk> all, String question, int budget) {
    Set<String> terms = terms(question);
    List<Set<String>> chunkTerms = all.stream().map(chunk -> tokens(chunk.text())).toList();
    Map<String, Integer> documentFrequency = new HashMap<>();
    for (Set<String> tokens : chunkTerms) {
      for (String term : terms) {
        if (matches(tokens, term)) {
          documentFrequency.merge(term, 1, Integer::sum);
        }
      }
    }
    List<Integer> ranked = new ArrayList<>();
    double[] scores = new double[all.size()];
    for (int i = 0; i < all.size(); i++) {
      double score = 0;
      for (String term : terms) {
        if (matches(chunkTerms.get(i), term)) {
          score += Math.log(1.0 + (double) all.size() / documentFrequency.get(term));
        }
      }
      scores[i] = score;
      if (score > 0) {
        ranked.add(i);
      }
    }
    ranked.sort(Comparator.comparingDouble((Integer i) -> -scores[i]).thenComparingInt(i -> i));
    if (ranked.isEmpty()) {
      ranked = openingChunksInTurn(all);
    }
    List<ChatAttachmentChunkStore.Chunk> chosen = new ArrayList<>();
    int used = 0;
    for (int i : ranked) {
      ChatAttachmentChunkStore.Chunk chunk = all.get(i);
      if (used + chunk.text().length() > budget && !chosen.isEmpty()) {
        continue;
      }
      chosen.add(chunk);
      used += chunk.text().length();
    }
    return chosen;
  }

  /** The first chunk of every attachment, then the second of every attachment, and so on. */
  private static List<Integer> openingChunksInTurn(List<ChatAttachmentChunkStore.Chunk> all) {
    Map<UUID, List<Integer>> positions = new LinkedHashMap<>();
    for (int i = 0; i < all.size(); i++) {
      positions.computeIfAbsent(all.get(i).attachmentId(), id -> new ArrayList<>()).add(i);
    }
    List<Integer> inTurn = new ArrayList<>();
    for (int round = 0; inTurn.size() < all.size(); round++) {
      for (List<Integer> ofAttachment : positions.values()) {
        if (round < ofAttachment.size()) {
          inTurn.add(ofAttachment.get(round));
        }
      }
    }
    return inTurn;
  }

  /** A term matches a token it equals, and one it starts, so "Frist" finds "Fristablauf". */
  private static boolean matches(Set<String> tokens, String term) {
    if (tokens.contains(term)) {
      return true;
    }
    if (term.length() < 5) {
      return false;
    }
    for (String token : tokens) {
      if (token.startsWith(term)) {
        return true;
      }
    }
    return false;
  }

  static Set<String> terms(String question) {
    Set<String> terms = new HashSet<>(tokens(question == null ? "" : question));
    terms.removeIf(term -> term.length() < MIN_TERM_LENGTH || STOP_WORDS.contains(term));
    return terms;
  }

  private static Set<String> tokens(String text) {
    Set<String> tokens = new HashSet<>();
    for (String token : NON_WORD.split(text.toLowerCase(Locale.GERMAN))) {
      if (!token.isEmpty()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private static Document passage(
      ChatAttachmentChunkStore.Chunk chunk, Map<UUID, ChatAttachment> attachments) {
    Map<String, Object> metadata = new HashMap<>(chunk.metadata());
    metadata.put("document_id", chunk.attachmentId().toString());
    metadata.put("chunk_index", chunk.index());
    metadata.put("file_name", attachments.get(chunk.attachmentId()).getFileName());
    metadata.put(ATTACHMENT_METADATA_KEY, true);
    return new Document(chunk.attachmentId() + "#" + chunk.index(), chunk.text(), metadata);
  }
}
