package io.opaa.indexing.source.filesystem;

import java.util.ArrayList;
import java.util.List;

/**
 * One exclusion pattern, matched level by level without backtracking blow-up: a level {@code **}
 * stands for any number of levels including none, {@code *} for any characters within one level,
 * {@code ?} for one character, {@code [abc]}/{@code [a-z]}/{@code [!abc]} for one character of a
 * class, and {@code {a,b}} for alternatives. Both the levels and the characters within one are
 * matched greedily with a single resumption point, so a match costs at most the product of pattern
 * and name length. Matching is case-sensitive on every platform.
 *
 * <p>A trailing {@code **} matches the directory it follows only as a directory: {@code Archiv/**}
 * covers the folder {@code Archiv} and everything below it, never a file named {@code Archiv}.
 */
final class FilesystemGlob {

  /** Upper bound of the alternatives {@code {a,b}} groups may expand into. */
  static final int MAX_ALTERNATIVES = 32;

  private static final String ANY_LEVELS = "**";

  /** One alternative, split into levels; a {@code null} level is {@link #ANY_LEVELS}. */
  private final List<List<Segment>> alternatives;

  private final boolean trailingAnyLevels;

  private FilesystemGlob(List<List<Segment>> alternatives, boolean trailingAnyLevels) {
    this.alternatives = alternatives;
    this.trailingAnyLevels = trailingAnyLevels;
  }

  /**
   * @throws IllegalArgumentException with a German reason when {@code pattern} is no valid glob
   */
  static FilesystemGlob compile(String pattern) {
    List<String> expanded = expandAlternatives(pattern);
    List<List<Segment>> alternatives = new ArrayList<>();
    boolean trailing = false;
    for (String alternative : expanded) {
      List<Segment> levels = new ArrayList<>();
      String[] parts = alternative.split("/", -1);
      for (String part : parts) {
        if (part.isEmpty()) {
          throw new IllegalArgumentException("enthält eine leere Ebene („//“ oder „/“ am Ende)");
        }
        if (part.equals(".") || part.equals("..")) {
          throw new IllegalArgumentException("enthält die Ebene „" + part + "“");
        }
        levels.add(part.equals(ANY_LEVELS) ? null : Segment.compile(part));
      }
      trailing |= levels.getLast() == null;
      alternatives.add(levels);
    }
    return new FilesystemGlob(alternatives, trailing);
  }

  /**
   * Whether the entry at {@code levels} (its path relative to the source, split by level) matches.
   */
  boolean matches(List<String> levels, boolean directory) {
    for (List<Segment> alternative : alternatives) {
      List<Segment> effective = alternative;
      if (trailingAnyLevels && !directory && alternative.getLast() == null) {
        // a file must lie below the directory, not be it
        effective = new ArrayList<>(alternative);
        effective.add(Segment.compile("*"));
      }
      if (matchLevels(effective, levels)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchLevels(List<Segment> pattern, List<String> levels) {
    int p = 0;
    int l = 0;
    int resumePattern = -1;
    int resumeLevel = 0;
    while (l < levels.size()) {
      if (p < pattern.size() && pattern.get(p) == null) {
        resumePattern = p++;
        resumeLevel = l;
      } else if (p < pattern.size() && pattern.get(p).matches(levels.get(l))) {
        p++;
        l++;
      } else if (resumePattern >= 0) {
        p = resumePattern + 1;
        l = ++resumeLevel;
      } else {
        return false;
      }
    }
    while (p < pattern.size() && pattern.get(p) == null) {
      p++;
    }
    return p == pattern.size();
  }

  private static List<String> expandAlternatives(String pattern) {
    List<String> result = new ArrayList<>(List.of(""));
    int i = 0;
    while (i < pattern.length()) {
      char c = pattern.charAt(i);
      if (c == '}') {
        throw new IllegalArgumentException("enthält eine „}“ ohne öffnende „{“");
      }
      if (c != '{') {
        int next = i;
        while (next < pattern.length()
            && pattern.charAt(next) != '{'
            && pattern.charAt(next) != '}') {
          next++;
        }
        String literal = pattern.substring(i, next);
        result.replaceAll(prefix -> prefix + literal);
        i = next;
        continue;
      }
      int close = pattern.indexOf('}', i);
      if (close < 0) {
        throw new IllegalArgumentException("enthält eine „{“ ohne schließende „}“");
      }
      String body = pattern.substring(i + 1, close);
      if (body.indexOf('{') >= 0) {
        throw new IllegalArgumentException("enthält verschachtelte „{…}“");
      }
      List<String> options = List.of(body.split(",", -1));
      if (result.size() * options.size() > MAX_ALTERNATIVES) {
        throw new IllegalArgumentException(
            "ergibt mehr als " + MAX_ALTERNATIVES + " Alternativen aus „{…}“");
      }
      List<String> combined = new ArrayList<>();
      for (String prefix : result) {
        for (String option : options) {
          combined.add(prefix + option);
        }
      }
      result = combined;
      i = close + 1;
    }
    return result;
  }

  /** One level of a pattern: literal characters, {@code ?}, character classes and {@code *}. */
  private static final class Segment {

    private static final int STAR = -1;

    /** Per position: a character class, or {@code null} for {@link #STAR}. */
    private final List<CharClass> tokens;

    private Segment(List<CharClass> tokens) {
      this.tokens = tokens;
    }

    static Segment compile(String text) {
      List<CharClass> tokens = new ArrayList<>();
      int i = 0;
      while (i < text.length()) {
        char c = text.charAt(i);
        if (c == '*') {
          if (tokens.isEmpty() || tokens.getLast() != null) {
            tokens.add(null);
          }
          i++;
        } else if (c == '?') {
          tokens.add(CharClass.ANY);
          i++;
        } else if (c == '[') {
          int close = text.indexOf(']', i + 2);
          if (close < 0) {
            throw new IllegalArgumentException("enthält eine „[“ ohne schließende „]“");
          }
          tokens.add(CharClass.parse(text.substring(i + 1, close)));
          i = close + 1;
        } else {
          tokens.add(CharClass.literal(c));
          i++;
        }
      }
      return new Segment(tokens);
    }

    boolean matches(String name) {
      int t = 0;
      int n = 0;
      int resumeToken = STAR;
      int resumeName = 0;
      while (n < name.length()) {
        if (t < tokens.size() && tokens.get(t) == null) {
          resumeToken = t++;
          resumeName = n;
        } else if (t < tokens.size() && tokens.get(t).contains(name.charAt(n))) {
          t++;
          n++;
        } else if (resumeToken != STAR) {
          t = resumeToken + 1;
          n = ++resumeName;
        } else {
          return false;
        }
      }
      while (t < tokens.size() && tokens.get(t) == null) {
        t++;
      }
      return t == tokens.size();
    }
  }

  /** Inclusive character ranges, alternating from/to in {@code bounds}. */
  private record CharClass(char[] bounds, boolean negated, boolean any) {

    static final CharClass ANY = new CharClass(new char[0], false, true);

    static CharClass literal(char c) {
      return new CharClass(new char[] {c, c}, false, false);
    }

    /** {@code body} is the text between the brackets, never empty. */
    static CharClass parse(String body) {
      boolean negated = body.charAt(0) == '!' || body.charAt(0) == '^';
      String members = negated ? body.substring(1) : body;
      if (members.isEmpty()) {
        throw new IllegalArgumentException("enthält eine leere Zeichenklasse „[…]“");
      }
      StringBuilder bounds = new StringBuilder();
      for (int i = 0; i < members.length(); i++) {
        char from = members.charAt(i);
        char to = from;
        if (i + 2 < members.length() && members.charAt(i + 1) == '-') {
          to = members.charAt(i + 2);
          if (to < from) {
            throw new IllegalArgumentException(
                "enthält einen ungültigen Bereich „" + from + "-" + to + "“");
          }
          i += 2;
        }
        bounds.append(from).append(to);
      }
      return new CharClass(bounds.toString().toCharArray(), negated, false);
    }

    boolean contains(char c) {
      if (any) {
        return true;
      }
      boolean member = false;
      for (int i = 0; i < bounds.length && !member; i += 2) {
        member = c >= bounds[i] && c <= bounds[i + 1];
      }
      return member != negated;
    }
  }
}
