package io.opaa.indexing.pipeline.confluence;

import java.util.Set;

/**
 * The rule set per macro class (ADR-0023) - the one place that decides what of a Confluence macro
 * becomes indexed text. Documented in docs/features/ingestion-pipelines.md, Teil 3, Punkt 6; every
 * class has a test in {@code ConfluenceDocumentPipelineTest}.
 *
 * <p>The dividing line is <em>where the content lives</em>: text an author typed into the page (a
 * note, a panel, an expand, a code block) is page content and stays; what Confluence assembles at
 * view time from other pages, labels, Jira or a remote system is not - it is navigation or a copy
 * of data that lives elsewhere, and the epic keeps macros with their own data store out of scope.
 * An unknown macro is treated as static: a rich-text body it carries is content the author wrote;
 * nothing else of it is.
 */
final class ConfluenceMacroRules {

  enum Rule {
    /** Rendered at view time from elsewhere - nothing of the macro is page content. */
    DROP,
    /** The macro's title parameter is its visible text, inline (a status lozenge). */
    INLINE_TITLE,
    /** The plain-text body is kept with its line breaks (code, noformat). */
    VERBATIM,
    /** The title parameter (if any) and the rich-text body are page content; parameters are not. */
    KEEP_BODY
  }

  /**
   * Navigation aids and dynamic reports: tables of contents, child and sibling lists, label
   * reports, recently updated, search boxes, template buttons, attachment lists and galleries (the
   * attachments themselves are indexed as documents of their own), task and page-property reports,
   * charts, calendars, profiles and user lists, embedded web content, and everything that copies
   * data from another system (Jira, SQL, RSS).
   */
  private static final Set<String> DYNAMIC =
      Set.of(
          "toc",
          "toc-zone",
          "children",
          "pagetree",
          "pagetreesearch",
          "recently-updated",
          "recently-updated-dashboard",
          "contentbylabel",
          "contentbyuser",
          "listlabels",
          "popular-labels",
          "related-labels",
          "labels-list",
          "livesearch",
          "create-from-template",
          "attachments",
          "gallery",
          "viewfile",
          "view-file",
          "tasks-report-macro",
          "tasks-report",
          "detailssummary",
          "page-properties-report",
          "chart",
          "calendar",
          "roadmap",
          "blog-posts",
          "index",
          "spaces",
          "spacedetails",
          "favpages",
          "profile",
          "profile-picture",
          "userlister",
          "contributors",
          "contributors-summary",
          "change-history",
          "widget",
          "iframe",
          "html",
          "html-include",
          "rss",
          "jira",
          "jiraissues",
          "jirachart",
          "jiraroadmap",
          "sql",
          "sql-query",
          "include",
          "excerpt-include",
          "multiexcerpt-include",
          "loremipsum",
          "recently-used-labels",
          "navmap",
          "network",
          "space-attachments",
          "team-calendar",
          "cheese");

  private static final Set<String> VERBATIM = Set.of("code", "noformat");

  private static final Set<String> INLINE_TITLE = Set.of("status", "lozenge");

  private ConfluenceMacroRules() {}

  static Rule ruleFor(String macroName) {
    if (DYNAMIC.contains(macroName)) {
      return Rule.DROP;
    }
    if (VERBATIM.contains(macroName)) {
      return Rule.VERBATIM;
    }
    if (INLINE_TITLE.contains(macroName)) {
      return Rule.INLINE_TITLE;
    }
    return Rule.KEEP_BODY;
  }
}
