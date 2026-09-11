package io.opaa.query.retrieval.search;

import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.FullTextIdentifiers;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.retrieval.scope.MetadataFilterExpressions;
import io.opaa.query.retrieval.scope.MetadataFilterStage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The lexical half of the hybrid search (docs/handbuch/suche.md, Stufe 5): one PostgreSQL full-text
 * query against {@code chunk_full_text}, ranked by {@code ts_rank}, in the {@link Document} shape
 * the vector path returns. The permission filter is part of that query, never a filter on its
 * result (ADR-0008 §5): {@code library_id = ANY(?)} sits in the {@code WHERE} clause.
 *
 * <p>The query mirrors {@link FullTextChunkStore#indexChunks}: the German analysis chain over the
 * question's words, OR-ed rather than AND-ed because this path feeds a fusion, plus the identifier
 * lexemes of {@link FullTextIdentifiers} - weight {@code A}, which keeps "§ 34" and "§ 35" apart.
 * Both halves use sanitized tokens, so no character of the question reaches {@code to_tsquery} as
 * an operator.
 *
 * <p>Two accepted limits: {@code ts_rank} is not BM25, which the fusion tolerates because it
 * consumes ranks; and an older {@code content_tsv_version} row is searched unfiltered (ADR-0028),
 * valid only while a version raise stays additive.
 */
@Component
public class FullTextChunkSearch {

  /**
   * Upper bound on word tokens taken from one question. A tsquery grows linearly in tokens and a
   * pathologically long question would otherwise put hundreds of {@code OR} branches on the GIN
   * index; real questions and sub-queries stay far below this.
   */
  static final int MAX_QUERY_TOKENS = 32;

  /**
   * Upper bound on the length of a single word token, in characters. PostgreSQL rejects a lexeme of
   * 2047 bytes or more in {@code to_tsquery}, and a question of multi-byte letters without a
   * separator reaches that within the 2000 characters a question may have - the query would fail on
   * input no operator can recognize as malformed. 500 stays below the limit at UTF-8's worst case
   * of three bytes per character, and costs nothing real: no word comes close, and a truncated
   * token still matches the stems its full form would.
   */
  static final int MAX_QUERY_TOKEN_LENGTH = 500;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final String schemaName;
  private final String tableName;

  FullTextChunkSearch(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.schemaName = schemaName;
    this.tableName = tableName;
  }

  /**
   * The best {@code limit} chunks for {@code question} within {@code libraryIds}, best first, with
   * the core-field filter as further {@code WHERE} conditions over the chunk's {@code metadata} -
   * the lexical twin of the vector path's filter expression, built by {@link
   * MetadataFilterExpressions#sqlPredicate} from the same rule, and placed after the permission
   * filter in the same clause: it narrows, it never widens.
   *
   * <p>Returns an empty list without touching the database when there is nothing that could match -
   * no libraries, no limit, or a question that carries neither a usable word token nor an
   * identifier. An empty {@code libraryIds} in particular must never widen into "all libraries"; it
   * means the caller resolved no readable library at all.
   *
   * @param vocabularyCodes the complete Dokumentart value set the "no value" condition is built
   *     over - the one snapshot {@link MetadataFilterStage} read for the run, so every sub-query of
   *     both paths filters against the same vocabulary. Ignored unless the filter constrains the
   *     Dokumentart.
   */
  public List<Document> search(
      String question,
      Collection<UUID> libraryIds,
      MetadataFilter metadataFilter,
      Collection<String> vocabularyCodes,
      int limit) {
    if (libraryIds.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<String> wordTokens = wordTokens(question);
    List<String> identifierLexemes = FullTextIdentifiers.extract(question);
    if (wordTokens.isEmpty() && identifierLexemes.isEmpty()) {
      return List.of();
    }

    List<String> tsQueryParameters = new ArrayList<>();
    String tsQueryExpression = tsQueryExpression(wordTokens, identifierLexemes, tsQueryParameters);
    List<Object> metadataParameters = new ArrayList<>();
    String metadataPredicate =
        MetadataFilterExpressions.sqlPredicate(
            metadataFilter, "v.metadata", vocabularyCodes, metadataParameters);
    String sql =
        "WITH q AS (SELECT "
            + tsQueryExpression
            + " AS tsq) "
            + "SELECT v.id AS chunk_id, v.content AS content, v.metadata AS metadata, "
            + "       ts_rank(f.content_tsv, q.tsq) AS rank "
            + "FROM chunk_full_text f "
            + "JOIN "
            + schemaName
            + "."
            + tableName
            + " v ON v.id = f.chunk_id, q "
            + "WHERE f.library_id = ANY(?) "
            + "  AND f.content_tsv @@ q.tsq "
            + metadataPredicate
            // Ties in ts_rank are common - identically structured documents of one office score the
            // same for a question naming none of them. The tie-break is therefore derived from the
            // chunk's content, not from its identity: a chunk id and a document id are fresh UUIDs
            // per indexing run, so an id-based order silently reshuffles the tail between two runs
            // over the same corpus and costs the retrieval benchmark its run-to-run
            // reproducibility (ADR-0013). That is also why document_id is deliberately *not* an
            // earlier key than file_name, tempting as it would be for disambiguation.
            //
            // chunk_index is cast to int because "10" sorts before "2" as text; the value is
            // written as a number by DocumentIngestService and by nothing else. The id remains the
            // last key so the order is total.
            //
            // Honest limit: two documents that share a file name - possible across libraries, or
            // after the same file was uploaded twice - fall through to the id and are therefore
            // ordered stably within one database, but not identically across a re-indexing run.
            // Stronger would need a content hash per chunk, which no column carries today.
            + "ORDER BY rank DESC, v.metadata->>'file_name',"
            + " (v.metadata->>'chunk_index')::int, chunk_id "
            + "LIMIT ?";

    UUID[] libraries = libraryIds.toArray(UUID[]::new);
    return jdbcTemplate.query(
        connection -> {
          var statement = connection.prepareStatement(sql);
          int index = 1;
          for (String parameter : tsQueryParameters) {
            statement.setString(index++, parameter);
          }
          statement.setArray(index++, connection.createArrayOf("uuid", libraries));
          for (Object parameter : metadataParameters) {
            if (parameter instanceof String[] codes) {
              statement.setArray(index++, connection.createArrayOf("text", codes));
            } else {
              statement.setString(index++, parameter.toString());
            }
          }
          statement.setInt(index, limit);
          return statement;
        },
        (rs, rowNum) ->
            Document.builder()
                .id(rs.getString("chunk_id"))
                .text(rs.getString("content"))
                .metadata(readMetadata(rs.getString("metadata")))
                .score((double) rs.getFloat("rank"))
                .build());
  }

  /**
   * The {@code tsquery} expression, with its bind values appended to {@code parameters} in the
   * order the expression consumes them. Built as an expression with placeholders rather than as an
   * assembled query string: the tokens are sanitized, but a query text is still user input, and
   * "sanitized, therefore safe to concatenate" is the reasoning that produces injection defects.
   */
  private String tsQueryExpression(
      List<String> wordTokens, List<String> identifierLexemes, List<String> parameters) {
    if (identifierLexemes.isEmpty()) {
      parameters.add(FullTextChunkStore.TEXT_SEARCH_CONFIGURATION);
      parameters.add(String.join(" | ", wordTokens));
      return "to_tsquery(?::regconfig, ?)";
    }
    if (wordTokens.isEmpty()) {
      parameters.add(String.join(" | ", identifierLexemes));
      return "to_tsquery('simple', ?)";
    }
    parameters.add(FullTextChunkStore.TEXT_SEARCH_CONFIGURATION);
    parameters.add(String.join(" | ", wordTokens));
    parameters.add(String.join(" | ", identifierLexemes));
    return "(to_tsquery(?::regconfig, ?) || to_tsquery('simple', ?))";
  }

  /**
   * The question's words as {@code to_tsquery}-safe tokens: lowercased, split at everything that is
   * not a letter or digit, deduplicated, each capped at {@link #MAX_QUERY_TOKEN_LENGTH} characters
   * and the list at {@link #MAX_QUERY_TOKENS} entries. Stemming and stopword removal are left to
   * the {@code german} configuration the tokens are handed to - doing either here would be a
   * second, drifting copy of the analysis chain the index was built with.
   */
  static List<String> wordTokens(String question) {
    if (question == null || question.isBlank()) {
      return List.of();
    }
    Set<String> tokens = new LinkedHashSet<>();
    StringBuilder current = new StringBuilder();
    for (char character : question.toLowerCase(Locale.GERMAN).toCharArray()) {
      if (Character.isLetterOrDigit(character)) {
        current.append(character);
      } else if (current.length() > 0) {
        tokens.add(truncated(current));
        current.setLength(0);
      }
    }
    if (current.length() > 0) {
      tokens.add(truncated(current));
    }
    List<String> result = new ArrayList<>(tokens);
    return result.size() <= MAX_QUERY_TOKENS
        ? List.copyOf(result)
        : List.copyOf(result.subList(0, MAX_QUERY_TOKENS));
  }

  /** Never cuts a surrogate pair in half - half a pair is not encodable as UTF-8. */
  private static String truncated(StringBuilder token) {
    if (token.length() <= MAX_QUERY_TOKEN_LENGTH) {
      return token.toString();
    }
    int end = MAX_QUERY_TOKEN_LENGTH;
    if (Character.isHighSurrogate(token.charAt(end - 1))) {
      end--;
    }
    return token.substring(0, end);
  }

  /**
   * The chunk's metadata as {@code similaritySearch} would have returned it. Null-valued keys are
   * dropped: {@link Document} rejects them outright, and a chunk must not become unretrievable
   * through the lexical path over a metadata key the vector path never reads either.
   */
  private Map<String, Object> readMetadata(String metadataJson) {
    if (metadataJson == null || metadataJson.isBlank()) {
      return Map.of();
    }
    Map<String, Object> metadata =
        objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
    metadata.values().removeIf(java.util.Objects::isNull);
    return metadata;
  }
}
