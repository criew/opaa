package io.opaa.agent;

import io.opaa.agent.AgentDirectory.AgentEntry;
import io.opaa.agent.AgentDirectory.Responsibility;
import io.opaa.agent.AgentDirectory.TemplateEntry;
import io.opaa.api.types.AgentPlanStep;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the agent directory as CSV for the annex of a service agreement: UTF-8 with byte order
 * mark and semicolons, as a German spreadsheet opens it, one row per agent and per template. The
 * labels are the interface's. A cell that a spreadsheet would read as a formula is prefixed with an
 * apostrophe, because purpose and instruction are free text.
 */
final class AgentDirectoryCsv {

  static final List<String> HEADER =
      List.of(
          "Art",
          "Name",
          "Zweck",
          "Anweisung",
          "Bibliotheken",
          "Werkzeuge",
          "Ablauf",
          "Grenzen",
          "Vorlagen",
          "Datei",
          "Platzhalter",
          "Verantwortliche Stelle",
          "Fassung",
          "Gesperrt seit");

  private static final Map<String, String> TOOL_LABELS =
      Map.of(
          AgentTools.SEARCH_KNOWLEDGE, "Im Wissen suchen",
          AgentTools.READ_DOCUMENT, "Dokument lesen",
          AgentTools.LIST_SOURCES, "Bestand auflisten",
          AgentTools.RUN_CODE, "Rechnen",
          AgentTools.ASK_USER, "Rückfragen",
          AgentTools.FILL_TEMPLATE, "Vorlage ausfüllen");

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private static final String LIST_SEPARATOR = ", ";

  private AgentDirectoryCsv() {}

  static final String ALL_READABLE_LIBRARIES =
      "Alle Bibliotheken, die die fragende Person lesen darf";
  static final String NO_VISIBLE_LIBRARY =
      "keine einsehbare Bibliothek (gelöscht oder nicht einsehbar)";

  /**
   * All readable only without any assignment; an assignment whose libraries are all gone or private
   * is none.
   */
  static String libraries(AgentEntry agent) {
    if (agent.allReadableLibraries()) {
      return ALL_READABLE_LIBRARIES;
    }
    return agent.libraryNames().isEmpty()
        ? NO_VISIBLE_LIBRARY
        : String.join(LIST_SEPARATOR, agent.libraryNames());
  }

  static byte[] render(AgentDirectory directory, ZoneId zone) {
    StringBuilder out = new StringBuilder("﻿");
    line(out, HEADER);
    for (AgentEntry agent : directory.agents()) {
      line(
          out,
          List.of(
              agent.delivered() ? "Agent (mitgeliefert)" : "Agent",
              agent.name(),
              nullToEmpty(agent.description()),
              nullToEmpty(agent.instruction()),
              libraries(agent),
              String.join(
                  LIST_SEPARATOR,
                  agent.toolNames().stream()
                      .map(name -> TOOL_LABELS.getOrDefault(name, name))
                      .toList()),
              agent.planStep() == AgentPlanStep.CONFIRM
                  ? "Plan bestätigen lassen"
                  : "Gleich loslegen",
              limits(agent.limits()),
              String.join(LIST_SEPARATOR, agent.templateNames()),
              "",
              "",
              responsibility(agent.responsibility()),
              Integer.toString(agent.revision()),
              date(agent.lockedSince(), zone)));
    }
    for (TemplateEntry template : directory.templates()) {
      line(
          out,
          List.of(
              "Vorlage",
              template.name(),
              nullToEmpty(template.description()),
              "",
              "",
              "",
              "",
              "",
              "",
              template.fileName(),
              String.join(LIST_SEPARATOR, template.placeholderNames()),
              responsibility(template.responsibility()),
              Integer.toString(template.revision()),
              date(template.lockedSince(), zone)));
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String limits(AgentLimits limits) {
    List<String> own = new ArrayList<>();
    if (limits.maxToolCalls() != null) {
      own.add("Werkzeugaufrufe: " + limits.maxToolCalls());
    }
    if (limits.maxSearchCalls() != null) {
      own.add("Suchen: " + limits.maxSearchCalls());
    }
    if (limits.maxDurationSeconds() != null) {
      own.add("Laufzeit: " + limits.maxDurationSeconds() + " Sekunden");
    }
    if (limits.maxCollectedChars() != null) {
      own.add("Lesemenge: " + limits.maxCollectedChars() + " Zeichen");
    }
    return own.isEmpty() ? "Grenzen der Installation" : String.join(LIST_SEPARATOR, own);
  }

  static String responsibility(Responsibility responsibility) {
    List<String> parts = new ArrayList<>();
    if (responsibility.groupName() != null) {
      parts.add(responsibility.groupName());
    }
    if (responsibility.function() != null) {
      parts.add(responsibility.function());
    }
    String named = String.join(LIST_SEPARATOR, parts);
    if (!responsibility.missing()) {
      return named;
    }
    return named.isEmpty() ? "fehlt" : named + " (fehlt)";
  }

  private static String date(Instant instant, ZoneId zone) {
    return instant == null ? "" : DATE.format(instant.atZone(zone));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void line(StringBuilder out, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(';');
      }
      out.append(cell(cells.get(i)));
    }
    out.append("\r\n");
  }

  /** Quoted always; a leading formula character is neutralised (CSV injection). */
  static String cell(String value) {
    String safe = value;
    if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
      safe = "'" + safe;
    }
    return '"' + safe.replace("\"", "\"\"") + '"';
  }
}
