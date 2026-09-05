package io.opaa.query;

import io.opaa.api.types.DatePrecision;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.metadata.CoreMetadataChunkKeys;
import io.opaa.indexing.metadata.LibraryFieldCondition;
import io.opaa.indexing.metadata.LibraryMetadataFieldKeys;
import io.opaa.indexing.metadata.MetadataFilter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * The one place a {@link MetadataFilter} becomes a query condition - once for the vector path as a
 * Spring AI {@link Filter.Expression}, once for the lexical path as SQL over the same {@code
 * vector_store.metadata} keys (ADR-0024, Entscheidung 5). Both forms state the identical rule:
 *
 * <pre>
 *   (doc_type in selected  OR doc_type absent)
 *   AND (doc_date within window at its precision  OR doc_date absent)
 * </pre>
 *
 * "Absent" is the Leerwert rule (metadata-schema.md, "Leerwerte schließen nicht aus"): a chunk
 * without the key is a document without a value, and it stays in. The pgvector filter converter
 * knows no {@code IS NULL}, so both forms express absence the same way: {@code NOT IN} over the
 * complete set of values the key can carry - the Dokumentart vocabulary, or the three precisions -
 * which is true for a chunk without the key. <b>Parity between the paths rests on that set being
 * closed:</b> a value outside it (a vocabulary code removed while chunks still carry it, a
 * precision no constant names) reads as "absent" and is kept - in both paths alike, because the SQL
 * form deliberately mirrors the {@code NOT IN} instead of saying {@code IN selected OR IS NULL}.
 *
 * <p><b>Brackets are explicit.</b> The pgvector converter renders a nested {@link
 * Filter.Expression} without parentheses; only a {@link Filter.Group} produces them, and jsonpath
 * binds {@code &&} tighter than {@code ||}. Every OR-composed condition is therefore wrapped in a
 * group before it enters an AND: the permission filter must be the outer operand of the
 * <em>whole</em> metadata condition, never of its first branch only.
 *
 * <p>The window at a precision: a stored value is the first day of the span its precision leaves
 * open, so {@code value <= to} and {@code value >= }{@link MetadataFilter#dateFromBound} is the
 * overlap of that span with the window.
 *
 * <p>Public for exactly one caller outside this package: the retrieval-evaluation harness ({@code
 * io.opaa.eval}) builds the raw-vector path's filter through {@link #vectorExpression} so that its
 * measurement applies the identical condition the production search applies.
 */
public final class MetadataFilterExpressions {

  private static final List<String> PRECISIONS =
      List.of(DatePrecision.DAY.name(), DatePrecision.MONTH.name(), DatePrecision.YEAR.name());

  private MetadataFilterExpressions() {}

  /**
   * The vector-path condition, or {@code null} for a filter that constrains nothing - including a
   * Dokumentart selection covering the whole {@code vocabularyCodes}, which every document
   * satisfies or lacks alike.
   */
  public static Filter.Expression vectorExpression(
      MetadataFilter filter, Collection<String> vocabularyCodes) {
    FilterExpressionBuilder b = new FilterExpressionBuilder();
    FilterExpressionBuilder.Op combined = null;
    if (filter.filtersDocumentType()) {
      List<Object> unselected =
          vocabularyCodes.stream()
              .filter(code -> !filter.documentTypes().contains(code))
              .map(Object.class::cast)
              .toList();
      if (!unselected.isEmpty()) {
        combined = b.group(b.nin(CoreMetadataChunkKeys.DOCUMENT_TYPE, unselected));
      }
    }
    if (filter.filtersDocumentDate()) {
      FilterExpressionBuilder.Op dateCondition = null;
      for (DatePrecision precision : DatePrecision.values()) {
        FilterExpressionBuilder.Op branch =
            b.eq(CoreMetadataChunkKeys.DOCUMENT_DATE_PRECISION, precision.name());
        if (filter.documentDateFrom() != null) {
          branch =
              b.and(
                  branch,
                  b.gte(
                      CoreMetadataChunkKeys.DOCUMENT_DATE,
                      filter.dateFromBound(precision).toString()));
        }
        if (filter.documentDateTo() != null) {
          branch =
              b.and(
                  branch,
                  b.lte(CoreMetadataChunkKeys.DOCUMENT_DATE, filter.documentDateTo().toString()));
        }
        branch = b.group(branch);
        dateCondition = dateCondition == null ? branch : b.or(dateCondition, branch);
      }
      dateCondition =
          b.group(
              b.or(
                  dateCondition,
                  b.nin(CoreMetadataChunkKeys.DOCUMENT_DATE_PRECISION, List.copyOf(PRECISIONS))));
      combined = combined == null ? dateCondition : b.and(combined, dateCondition);
    }
    for (LibraryFieldCondition condition : filter.libraryFields()) {
      FilterExpressionBuilder.Op libraryCondition = libraryFieldOp(b, condition);
      combined = combined == null ? libraryCondition : b.and(combined, libraryCondition);
    }
    return combined == null ? null : combined.build();
  }

  /**
   * One library-field condition (#1071) as {@code (foreign library OR matches OR no value)}. The
   * library guard is what makes {@code (libraryId, fieldKey)} the field identity: two libraries may
   * define the same key, and a document of the other one must not be judged against this field's
   * value list. "No value" reads the presence marker, whose value set is closed at exactly one
   * value - see {@link LibraryMetadataFieldKeys#PRESENCE_CHUNK_KEY_PREFIX} for why the value key
   * itself cannot carry that condition.
   */
  private static FilterExpressionBuilder.Op libraryFieldOp(
      FilterExpressionBuilder b, LibraryFieldCondition condition) {
    FilterExpressionBuilder.Op matches =
        switch (condition.type()) {
          case SELECT -> b.in(condition.chunkKey(), List.copyOf(condition.codes()));
          case PATTERN -> b.eq(condition.chunkKey(), condition.value());
          case DATE -> {
            FilterExpressionBuilder.Op window = null;
            for (DatePrecision precision : DatePrecision.values()) {
              FilterExpressionBuilder.Op branch =
                  b.eq(condition.precisionChunkKey(), precision.name());
              if (condition.dateFrom() != null) {
                branch =
                    b.and(
                        branch,
                        b.gte(condition.chunkKey(), condition.dateFromBound(precision).toString()));
              }
              if (condition.dateTo() != null) {
                branch = b.and(branch, b.lte(condition.chunkKey(), condition.dateTo().toString()));
              }
              branch = b.group(branch);
              window = window == null ? branch : b.or(window, branch);
            }
            yield window;
          }
        };
    FilterExpressionBuilder.Op noValue =
        b.nin(
            LibraryMetadataFieldKeys.presenceChunkKey(condition.fieldKey()),
            List.of(LibraryMetadataFieldKeys.PRESENCE_VALUE));
    FilterExpressionBuilder.Op foreignLibrary =
        b.nin(VectorChunkStore.LIBRARY_ID_METADATA_KEY, List.of(condition.libraryId().toString()));
    return b.group(b.or(b.or(foreignLibrary, matches), noValue));
  }

  /**
   * The permission filter with the metadata filter AND-ed to it - the order that makes the metadata
   * filter subordinate by construction: it can only ever remove from what {@code libraryFilter}
   * allows. A {@code null} metadata expression yields {@code libraryFilter} itself.
   */
  static Filter.Expression subordinateTo(
      Filter.Expression libraryFilter, Filter.Expression metadataExpression) {
    if (metadataExpression == null) {
      return libraryFilter;
    }
    return new Filter.Expression(
        Filter.ExpressionType.AND, libraryFilter, new Filter.Group(metadataExpression));
  }

  /**
   * The lexical-path condition as an SQL fragment over {@code metadataColumn} (a {@code json}
   * column), starting with {@code " AND "}, its bind values appended to {@code parameters} in the
   * order the fragment consumes them. Empty for a filter that constrains nothing. Each condition is
   * one bracketed {@code AND (...)} term, so it can only narrow what precedes it in the clause.
   */
  static String sqlPredicate(
      MetadataFilter filter,
      String metadataColumn,
      Collection<String> vocabularyCodes,
      List<Object> parameters) {
    StringBuilder sql = new StringBuilder();
    if (filter.filtersDocumentType()) {
      List<String> unselected =
          vocabularyCodes.stream().filter(code -> !filter.documentTypes().contains(code)).toList();
      if (!unselected.isEmpty()) {
        String typeKey = metadataColumn + "->>'" + CoreMetadataChunkKeys.DOCUMENT_TYPE + "'";
        sql.append(" AND (")
            .append(typeKey)
            .append(" IS NULL OR ")
            .append(typeKey)
            .append(" <> ALL(?))");
        parameters.add(unselected.toArray(String[]::new));
      }
    }
    if (filter.filtersDocumentDate()) {
      String dateKey = metadataColumn + "->>'" + CoreMetadataChunkKeys.DOCUMENT_DATE + "'";
      String precisionKey =
          metadataColumn + "->>'" + CoreMetadataChunkKeys.DOCUMENT_DATE_PRECISION + "'";
      sql.append(" AND (")
          .append(precisionKey)
          .append(" IS NULL OR ")
          .append(precisionKey)
          .append(" <> ALL(?)");
      parameters.add(PRECISIONS.toArray(String[]::new));
      for (DatePrecision precision : DatePrecision.values()) {
        sql.append(" OR (").append(precisionKey).append(" = ?");
        parameters.add(precision.name());
        if (filter.documentDateFrom() != null) {
          sql.append(" AND ").append(dateKey).append(" >= ?");
          parameters.add(filter.dateFromBound(precision).toString());
        }
        if (filter.documentDateTo() != null) {
          sql.append(" AND ").append(dateKey).append(" <= ?");
          parameters.add(filter.documentDateTo().toString());
        }
        sql.append(")");
      }
      sql.append(")");
    }
    for (LibraryFieldCondition condition : filter.libraryFields()) {
      appendLibraryFieldPredicate(condition, metadataColumn, sql, parameters);
    }
    return sql.toString();
  }

  /** The lexical twin of {@link #libraryFieldOp}, stating the identical rule. */
  private static void appendLibraryFieldPredicate(
      LibraryFieldCondition condition,
      String metadataColumn,
      StringBuilder sql,
      List<Object> parameters) {
    String valueKey = metadataColumn + "->>'" + condition.chunkKey() + "'";
    String presenceKey =
        metadataColumn
            + "->>'"
            + LibraryMetadataFieldKeys.presenceChunkKey(condition.fieldKey())
            + "'";
    String libraryKey = metadataColumn + "->>'" + VectorChunkStore.LIBRARY_ID_METADATA_KEY + "'";
    sql.append(" AND (")
        .append(libraryKey)
        .append(" IS NULL OR ")
        .append(libraryKey)
        .append(" <> ALL(?)");
    parameters.add(new String[] {condition.libraryId().toString()});
    sql.append(" OR ")
        .append(presenceKey)
        .append(" IS NULL OR ")
        .append(presenceKey)
        .append(" <> ALL(?)");
    parameters.add(new String[] {LibraryMetadataFieldKeys.PRESENCE_VALUE});
    switch (condition.type()) {
      case SELECT -> {
        sql.append(" OR ").append(valueKey).append(" = ANY(?)");
        parameters.add(condition.codes().toArray(String[]::new));
      }
      case PATTERN -> {
        sql.append(" OR ").append(valueKey).append(" = ?");
        parameters.add(condition.value());
      }
      case DATE -> {
        String precisionKey = metadataColumn + "->>'" + condition.precisionChunkKey() + "'";
        for (DatePrecision precision : DatePrecision.values()) {
          sql.append(" OR (").append(precisionKey).append(" = ?");
          parameters.add(precision.name());
          if (condition.dateFrom() != null) {
            sql.append(" AND ").append(valueKey).append(" >= ?");
            parameters.add(condition.dateFromBound(precision).toString());
          }
          if (condition.dateTo() != null) {
            sql.append(" AND ").append(valueKey).append(" <= ?");
            parameters.add(condition.dateTo().toString());
          }
          sql.append(")");
        }
      }
    }
    sql.append(")");
  }

  /** Whether {@code chunk} was kept by the Leerwert rule alone - see {@link MetadataFilter}. */
  static boolean keptWithoutValue(MetadataFilter filter, Document chunk) {
    Map<String, Object> metadata = chunk.getMetadata();
    Object type = metadata.get(CoreMetadataChunkKeys.DOCUMENT_TYPE);
    Object date = metadata.get(CoreMetadataChunkKeys.DOCUMENT_DATE);
    if (filter.keptWithoutValue(
        type == null ? null : type.toString(),
        date == null ? null : java.time.LocalDate.parse(date.toString()))) {
      return true;
    }
    Object libraryId = metadata.get(VectorChunkStore.LIBRARY_ID_METADATA_KEY);
    for (LibraryFieldCondition condition : filter.libraryFields()) {
      if (libraryId == null || !condition.libraryId().toString().equals(libraryId.toString())) {
        // A document of another library was never in this field's scope: it is neither matched nor
        // "kept without a value", and counting it as the latter would inflate the protocol note
        // with every document the condition was never about.
        continue;
      }
      if (metadata.get(LibraryMetadataFieldKeys.presenceChunkKey(condition.fieldKey())) == null) {
        return true;
      }
    }
    return false;
  }

  /** How many of {@code candidates} were kept by the Leerwert rule alone. */
  static int countKeptWithoutValue(MetadataFilter filter, List<Document> candidates) {
    if (filter.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (Document candidate : candidates) {
      if (keptWithoutValue(filter, candidate)) {
        count++;
      }
    }
    return count;
  }

  /** The selected codes, sorted, for the protocol note. */
  static String describeTypes(MetadataFilter filter) {
    return String.join(", ", filter.documentTypes().stream().sorted().toList());
  }
}
