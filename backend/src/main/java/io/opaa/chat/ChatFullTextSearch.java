package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import io.opaa.chat.ChatSearchMatch.Highlight;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one query of the chat search (docs/features/chat-list.md, "Chatsuche"): titles and messages
 * of the chats a person may see in one space, one match per chat, by {@code ts_rank} with the
 * German configuration of {@code chat_messages.content_tsv} (changeset 037).
 *
 * <p>The permission filter is the {@code visible} CTE and nothing else - every other part of the
 * query only ever reads from it, so a chat outside it is never loaded, ranked or counted. Shared
 * chats will widen exactly that CTE.
 *
 * <p>The term reaches the database only as bind values set on the statement directly, never through
 * {@code JdbcTemplate}'s argument binding, which traces parameter values.
 */
@Component
class ChatFullTextSearch {

  /** Must match the configuration of the generated column in changeset 037. */
  private static final String CONFIGURATION = "german";

  /** Upper bound of an excerpt in UTF-16 code units; ts_headline's word bound alone is not one. */
  static final int MAX_EXCERPT_LENGTH = 400;

  static final int MIN_PREFIX_LENGTH = 2;

  private static final char HIGHLIGHT_START = (char) 0xE000;
  private static final char HIGHLIGHT_END = (char) 0xE001;
  private static final char LESS_THAN = (char) 0xE002;
  private static final char GREATER_THAN = (char) 0xE003;

  /**
   * {@code ts_headline} replaces anything its parser takes for an HTML tag with a blank. Angle
   * brackets therefore travel as private-use placeholders and are restored in {@link #toMatchText};
   * the four private-use characters themselves are removed from the content first, so a marker in
   * the output can only be one ts_headline set.
   */
  private static final String TRANSLATE_FROM =
      "<>" + HIGHLIGHT_START + HIGHLIGHT_END + LESS_THAN + GREATER_THAN;

  private static final String TRANSLATE_TO = "" + LESS_THAN + GREATER_THAN;

  private static final String SELECTORS =
      "StartSel=\"" + HIGHLIGHT_START + "\", StopSel=\"" + HIGHLIGHT_END + "\"";
  private static final String MESSAGE_HEADLINE_OPTIONS = SELECTORS + ", MaxWords=30, MinWords=12";
  private static final String TITLE_HEADLINE_OPTIONS = SELECTORS + ", HighlightAll=true";

  private final JdbcTemplate jdbcTemplate;

  ChatFullTextSearch(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * One page of matches for {@code term} among the chats {@code userId} may see in {@code spaceId}.
   * Every word of the term must occur in the same message or in the title; the last word also
   * matches as a prefix. Ordered by rank, then by the time of the match (newest first), then by
   * chat id, so pages are stable.
   */
  ChatSearchPage search(UUID spaceId, UUID userId, String term, int page, int pageSize) {
    List<String> tsQueryParameters = new ArrayList<>();
    String tsQuery = tsQueryExpression(term, tsQueryParameters);
    String sql =
        "WITH q AS (SELECT "
            + tsQuery
            + " AS tsq), "
            + "visible AS ("
            + "  SELECT c.id, c.title, c.updated_at FROM chats c"
            + "  WHERE c.space_id = ? AND c.author_id = ?"
            + "), "
            + "message_hits AS ("
            + "  SELECT DISTINCT ON (m.chat_id) m.chat_id, m.id AS message_id, m.role,"
            + "         m.created_at, m.content, ts_rank(m.content_tsv, q.tsq) AS rank"
            + "  FROM chat_messages m JOIN visible v ON v.id = m.chat_id CROSS JOIN q"
            + "  WHERE m.content_tsv @@ q.tsq"
            + "  ORDER BY m.chat_id, rank DESC, m.sequence DESC"
            + "), "
            + "title_hits AS ("
            + "  SELECT v.id AS chat_id,"
            + "         ts_rank(to_tsvector('"
            + CONFIGURATION
            + "', v.title), q.tsq) AS rank"
            + "  FROM visible v CROSS JOIN q"
            + "  WHERE to_tsvector('"
            + CONFIGURATION
            + "', v.title) @@ q.tsq"
            + "), "
            + "ranked AS ("
            + "  SELECT v.id AS chat_id, v.title, mh.message_id, mh.role,"
            + "         mh.created_at AS message_created_at, mh.content,"
            + "         GREATEST(COALESCE(mh.rank, 0), COALESCE(th.rank, 0)) AS rank,"
            + "         COALESCE(mh.created_at, v.updated_at) AS hit_at"
            + "  FROM visible v"
            + "  LEFT JOIN message_hits mh ON mh.chat_id = v.id"
            + "  LEFT JOIN title_hits th ON th.chat_id = v.id"
            + "  WHERE mh.chat_id IS NOT NULL OR th.chat_id IS NOT NULL"
            + "  ORDER BY rank DESC, hit_at DESC, v.id"
            + "  LIMIT ? OFFSET ?"
            + ") "
            + "SELECT r.chat_id, r.title, r.message_id, r.role, r.message_created_at,"
            + "  CASE WHEN r.message_id IS NULL"
            + "    THEN ts_headline('"
            + CONFIGURATION
            + "', translate(r.title, ?, ?), q.tsq, ?)"
            + "    ELSE ts_headline('"
            + CONFIGURATION
            + "', translate(r.content, ?, ?), q.tsq, ?)"
            + "  END AS excerpt "
            + "FROM ranked r CROSS JOIN q "
            + "ORDER BY r.rank DESC, r.hit_at DESC, r.chat_id";

    List<ChatSearchMatch> rows =
        jdbcTemplate.query(
            connection -> {
              PreparedStatement statement = connection.prepareStatement(sql);
              int index = 1;
              for (String parameter : tsQueryParameters) {
                statement.setString(index++, parameter);
              }
              statement.setObject(index++, spaceId);
              statement.setObject(index++, userId);
              // One row beyond the page tells whether a further page exists.
              statement.setInt(index++, pageSize + 1);
              statement.setLong(index++, (long) page * pageSize);
              statement.setString(index++, TRANSLATE_FROM);
              statement.setString(index++, TRANSLATE_TO);
              statement.setString(index++, TITLE_HEADLINE_OPTIONS);
              statement.setString(index++, TRANSLATE_FROM);
              statement.setString(index++, TRANSLATE_TO);
              statement.setString(index, MESSAGE_HEADLINE_OPTIONS);
              return statement;
            },
            (rs, rowNum) -> toMatch(rs));
    boolean hasMore = rows.size() > pageSize;
    return new ChatSearchPage(hasMore ? rows.subList(0, pageSize) : rows, hasMore);
  }

  /**
   * The tsquery for {@code term}: {@code plainto_tsquery} over the whole term - the database's own
   * parser, so an identifier such as "12/4-2026" stays one lexeme as it was indexed - OR-ed with
   * the same term whose last word is a prefix. The prefix word is reduced to letters and digits, so
   * nothing of the term reaches {@code to_tsquery} as an operator.
   */
  static String tsQueryExpression(String term, List<String> parameters) {
    parameters.add(term);
    String exact = "plainto_tsquery('" + CONFIGURATION + "', ?)";
    int prefixStart = term.length();
    while (prefixStart > 0 && Character.isLetterOrDigit(term.charAt(prefixStart - 1))) {
      prefixStart--;
    }
    // A single trailing character as a prefix would match nearly every lexeme with that initial.
    if (term.length() - prefixStart < MIN_PREFIX_LENGTH) {
      return exact;
    }
    parameters.add(term.substring(0, prefixStart));
    parameters.add(term.substring(prefixStart));
    return "("
        + exact
        + " || (plainto_tsquery('"
        + CONFIGURATION
        + "', ?) && to_tsquery('"
        + CONFIGURATION
        + "', ? || ':*')))";
  }

  private static ChatSearchMatch toMatch(ResultSet rs) throws SQLException {
    UUID messageId = rs.getObject("message_id", UUID.class);
    String role = rs.getString("role");
    Timestamp messageCreatedAt = rs.getTimestamp("message_created_at");
    MatchText text = toMatchText(rs.getString("excerpt"));
    return new ChatSearchMatch(
        rs.getObject("chat_id", UUID.class),
        rs.getString("title"),
        null,
        messageId,
        role == null ? null : ChatRole.valueOf(role),
        messageCreatedAt == null ? null : messageCreatedAt.toInstant(),
        text.excerpt(),
        text.highlights());
  }

  record MatchText(String excerpt, List<Highlight> highlights) {}

  /**
   * Turns ts_headline's output into plain text and highlight offsets: the markers are removed and
   * become ranges, the placeholders become angle brackets again. Cut at {@link
   * #MAX_EXCERPT_LENGTH}, never inside a surrogate pair; a range reaching past the cut is clipped.
   */
  static MatchText toMatchText(String marked) {
    StringBuilder text = new StringBuilder();
    List<Highlight> highlights = new ArrayList<>();
    int openedAt = -1;
    for (int i = 0; i < marked.length(); i++) {
      char character = marked.charAt(i);
      switch (character) {
        case HIGHLIGHT_START -> openedAt = text.length();
        case HIGHLIGHT_END -> {
          if (openedAt >= 0 && openedAt < text.length()) {
            highlights.add(new Highlight(openedAt, text.length()));
          }
          openedAt = -1;
        }
        case LESS_THAN -> text.append('<');
        case GREATER_THAN -> text.append('>');
        default -> text.append(character);
      }
    }
    if (text.length() <= MAX_EXCERPT_LENGTH) {
      return new MatchText(text.toString(), highlights);
    }
    int cut = MAX_EXCERPT_LENGTH;
    if (Character.isHighSurrogate(text.charAt(cut - 1))) {
      cut--;
    }
    List<Highlight> clipped = new ArrayList<>();
    for (Highlight highlight : highlights) {
      if (highlight.start() < cut) {
        clipped.add(new Highlight(highlight.start(), Math.min(highlight.end(), cut)));
      }
    }
    return new MatchText(text.substring(0, cut), clipped);
  }
}
