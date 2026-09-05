package io.opaa.query;

import io.opaa.indexing.FullTextChunkStore;
import io.opaa.indexing.FullTextIdentifiers;
import io.opaa.indexing.metadata.MetadataFilter;
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
 * The lexical half of the hybrid search (docs/features/hybrid-retrieval.md, Arbeitspaket 2): one
 * PostgreSQL full-text query against {@code chunk_full_text}, ranked by {@code ts_rank}, returning
 * the same {@link Document} shape the vector path returns so both feed one fusion later (#1049).
 *
 * <p><b>The permission filter is part of the query, never a filter on its result</b> (ADR-0008 §5,
 * docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit). {@code library_id = ANY(?)}
 * sits in the {@code WHERE} clause next to the match predicate; there is no code path in this class
 * that reads a row of a library the caller did not pass in. A second search path is the most common
 * way a permission-aware search springs a leak, which is why this is a structural property here and
 * not a convention.
 *
 * <p><b>The query is built the same way the index is</b>, from two halves that mirror {@link
 * FullTextChunkStore#indexChunks} exactly:
 *
 * <ul>
 *   <li>the German analysis chain over the question's words, {@code to_tsquery(german, w1 | w2 |
 *       …)}. {@code OR}, not {@code AND}: this path supplies ranked candidates for a fusion, so a
 *       question whose every word must occur would return nothing for most real questions. {@code
 *       ts_rank} is what separates a chunk matching six terms from one matching one.
 *   <li>the undecomposed identifier lexemes of {@link FullTextIdentifiers}, {@code
 *       to_tsquery(simple, …)}, OR-ed on top. They carry weight {@code A} in the index, so an exact
 *       identifier match outranks the bare number the German chain left behind - the mechanism that
 *       keeps "§ 34" and "§ 35" apart.
 * </ul>
 *
 * <p>Both halves are built from sanitized tokens - word tokens are reduced to letters and digits,
 * identifier lexemes are ASCII-alphanumeric by construction - so no character of the user's
 * question can reach {@code to_tsquery} as an operator.
 *
 * <p><b>{@code ts_rank} is not BM25</b> and is knowingly used anyway: it lacks BM25's document
 * length normalization and inverse document frequency, so it overrates long chunks and
 * underseparates frequent from rare terms. Fusion consumes ranks rather than scores, which is a
 * markedly weaker requirement than ranking correctly (docs/features/hybrid-retrieval.md, "Die
 * bekannte Grenze: ts_rank ist kein BM25", and docs/handbuch/deployment.md for the operator-facing
 * statement of the same limit).
 *
 * <p>Schema/table name of the vector store are read from the same {@code
 * spring.ai.vectorstore.pgvector.*} properties {@code PgVectorStore} itself binds, mirroring {@link
 * ChunkEmbeddingLookup}.
 */
@Component
class FullTextChunkSearch {

  /**
   * Upper bound on word tokens taken from one question. A tsquery grows linearly in tokens and a
   * pathologically long question would otherwise put hundreds of {@code OR} branches on the GIN
   * index; real questions and sub-queries stay far below this.
   */
  static final int MAX_QUERY_TOKENS = 32;

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
   * The best {@code limit} chunks for {@code question} within {@code libraryIds}, best first.
   *
   * <p>Returns an empty list without touching the database when there is nothing that could match -
   * no libraries, no limit, or a question that carries neither a usable word token nor an
   * identifier. An empty {@code libraryIds} in particular must never widen into "all libraries"; it
   * means the caller resolved no readable library at all.
   */
  List<Document> search(String question, Collection<UUID> libraryIds, int limit) {
    return search(question, libraryIds, MetadataFilter.NONE, List.of(), limit);
  }

  /**
   * The same search with the core-field filter (#1070) as further {@code WHERE} conditions over the
   * chunk's {@code metadata} - the lexical twin of the vector path's filter expression, built by
   * {@link MetadataFilterExpressions#sqlPredicate} from the same rule, and placed after the
   * permission filter in the same clause: it narrows, it never widens.
   *
   * @param vocabularyCodes the complete Dokumentart value set the "no value" condition is built
   *     over - the one snapshot {@link MetadataFilterStage} read for the run, so every sub-query of
   *     both paths filters against the same vocabulary. Ignored unless the filter constrains the
   *     Dokumentart.
   */
  List<Document> search(
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
            + "  AND f.content_tsv_version = ? "
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
            // written as a number by FileProcessingService and by nothing else. The id remains the
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
          statement.setShort(index++, FullTextChunkStore.CURRENT_TSV_VERSION);
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
   * not a letter or digit, deduplicated, capped at {@link #MAX_QUERY_TOKENS}. Stemming and stopword
   * removal are left to the {@code german} configuration the tokens are handed to - doing either
   * here would be a second, drifting copy of the analysis chain the index was built with.
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
        tokens.add(current.toString());
        current.setLength(0);
      }
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    List<String> result = new ArrayList<>(tokens);
    return result.size() <= MAX_QUERY_TOKENS
        ? List.copyOf(result)
        : List.copyOf(result.subList(0, MAX_QUERY_TOKENS));
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
