/**
 * German wording for the free-text halves of the retrieval explanation protocol - the stage notes
 * and the candidate list labels. The backend states both technically and in English by contract
 * (`StageExplanation#notes`, `CandidateList`); turning them into the operator's language is this
 * layer's job, exactly as it is for the protocol's closed vocabularies.
 *
 * Parameter names (`fetch-k`, `top-k`, `mmr-lambda`) and configuration keys stay as they are: they
 * are what the operator types into a configuration file, so translating them would break the link
 * to the deployment handbook.
 */

/** A note the backend can produce, and the German sentence that carries the same statement. */
type NoteRule = {
  pattern: RegExp
  german: (match: RegExpMatchArray) => string
}

function plural(count: string, one: string, many: string) {
  return `${count} ${count === '1' ? one : many}`
}

const NOTE_RULES: NoteRule[] = [
  // SearchScopeStage
  {
    pattern: /^empty search scope: nothing readable in scope, retrieval halted$/,
    german: () =>
      'Leerer Suchbereich: In diesem Rechtekontext ist nichts lesbar, die Suche wurde abgebrochen.',
  },
  {
    pattern: /^search scope: (\d+) (?:library|libraries)$/,
    german: (m) => `Suchbereich: ${plural(m[1], 'Bibliothek', 'Bibliotheken')}.`,
  },
  {
    pattern: /^permission filter applied inside every search of this run, never afterwards$/,
    german: () =>
      'Rechtefilter in jeder Suche dieses Laufs angewendet, nie erst nachträglich auf das Ergebnis.',
  },

  // SubQueryDecompositionStage
  {
    pattern: /^decomposition produced (\d+) sub-quer(?:y|ies)$/,
    german: (m) => `Zerlegung hat ${plural(m[1], 'Teilfrage', 'Teilfragen')} erzeugt.`,
  },
  {
    pattern: /^decomposition returned nothing \(failed or unparsable\): single-query fallback$/,
    german: () =>
      'Zerlegung lieferte nichts (fehlgeschlagen oder nicht auswertbar): Rückfall auf eine einzelne Suchanfrage.',
  },
  {
    pattern: /^decomposition switched off by configuration: single-query fallback$/,
    german: () =>
      'Zerlegung per Konfiguration abgeschaltet: Rückfall auf eine einzelne Suchanfrage.',
  },
  { pattern: /^search query: (.*)$/s, german: (m) => `Suchanfrage: ${m[1]}` },

  // VectorSearchStage
  {
    pattern: /^vector search, (\d+) list\(s\)$/,
    german: (m) => `Vektorsuche über ${plural(m[1], 'Liste', 'Listen')}.`,
  },
  { pattern: /^fetch-k (\d+) per list$/, german: (m) => `fetch-k ${m[1]} je Liste.` },
  {
    pattern: /^similarity threshold (.+), applied in-query$/,
    german: (m) => `Ähnlichkeitsschwelle ${m[1]}, in der Suchanfrage angewendet.`,
  },

  // FullTextSearchStage
  {
    pattern: /^lexical search path switched off \((.+)\)$/,
    german: (m) => `Volltextpfad abgeschaltet (${m[1]}).`,
  },
  {
    pattern: /^no library of the search scope has a completed full-text backfill$/,
    german: () => 'Keine Bibliothek des Suchbereichs hat einen abgeschlossenen Volltext-Backfill.',
  },
  {
    pattern: /^the lexical path stays out of the fusion until a library's backfill is complete$/,
    german: () =>
      'Der Volltextpfad bleibt aus der Fusion heraus, bis der Backfill mindestens einer Bibliothek abgeschlossen ist.',
  },
  {
    // The exception type the backend appends is developer diagnostics and stays in the server log:
    // a Java class name says nothing to the operator that this sentence does not say better.
    pattern: /^lexical search failed for (.+?): \w+$/,
    german: (m) =>
      `Volltextsuche für die Liste „${translateListLabel(m[1])}“ fehlgeschlagen; der Lauf wurde ohne ihre Treffer fortgesetzt.`,
  },
  {
    pattern: /^full-text search, (\d+) list\(s\)$/,
    german: (m) => `Volltextsuche über ${plural(m[1], 'Liste', 'Listen')}.`,
  },
  {
    pattern:
      /^permission filter applied inside the query: (\d+) of (\d+) scoped libraries searched, the rest awaiting their backfill$/,
    german: (m) =>
      `Rechtefilter in der Suchanfrage angewendet: ${m[1]} von ${m[2]} Bibliotheken des Suchbereichs durchsucht, die übrigen warten auf ihren Backfill.`,
  },

  // MmrSelectionStage
  { pattern: /^per-list budget (\d+)$/, german: (m) => `Auswahlgrenze je Liste: ${m[1]}.` },
  {
    pattern: /^mmr-lambda (.+) \(diversity term inactive: plain top-k by relevance\)$/,
    german: (m) => `mmr-lambda ${m[1]} (Vielfaltsanteil inaktiv: reine Rangfolge nach Relevanz).`,
  },
  {
    pattern:
      /^mmr-lambda (.+) \(diversity term active, cosine similarity of real chunk embeddings\)$/,
    german: (m) =>
      `mmr-lambda ${m[1]} (Vielfaltsanteil aktiv, Kosinus-Ähnlichkeit der echten Abschnitts-Einbettungen).`,
  },

  // RankFusionStage
  {
    pattern: /^reciprocal rank fusion over (\d+) list\(s\)$/,
    german: (m) => `Reziproke Rangfusion über ${plural(m[1], 'Liste', 'Listen')}.`,
  },
  {
    pattern: /^budget widened to the rerank candidate window (\d+)$/,
    german: (m) => `Auswahlgrenze auf das Kandidatenfenster der Neubewertung erweitert: ${m[1]}.`,
  },
  { pattern: /^overall budget top-k (\d+)$/, german: (m) => `Gesamtgrenze top-k: ${m[1]}.` },
  {
    pattern: /^deduplicated by chunk id: (\d+) list entries became (\d+) distinct candidates$/,
    german: (m) =>
      `Nach Abschnitts-Kennung entdoppelt: Aus ${m[1]} Listeneinträgen wurden ${m[2]} verschiedene Kandidaten.`,
  },

  // RerankStage
  {
    pattern: /^reranking switched off through the rerank model role's own switch \((.+)\)$/,
    german: (m) => `Neubewertung über den Schalter der Modellrolle abgeschaltet (${m[1]}).`,
  },
  {
    pattern: /^reranking switched off through (.+)$/,
    german: (m) => `Neubewertung abgeschaltet über ${m[1]}.`,
  },
  {
    // The backend points at the role's status provider here; on this page the same answer stands a
    // few lines further up, in the card of the rerank model role.
    pattern: /^the rerank model role is switched on but was not usable when this run started/,
    german: () =>
      'Die Modellrolle Reranking ist eingeschaltet, war beim Start dieses Laufs aber nicht nutzbar: kein Endpunkt oder Modell hinterlegt, oder der Endpunkt hat nicht geantwortet. Welcher Fall vorliegt, steht oben im Zustand der Modellrolle.',
  },
  {
    pattern: /^no candidate reached this stage; there was nothing to rerank$/,
    german: () => 'Kein Kandidat hat diese Stufe erreicht; es war nichts neu zu bewerten.',
  },
  {
    pattern:
      /^the rerank model role scored nothing; the fused order was kept and capped at top-k (\d+)$/,
    german: (m) =>
      `Die Modellrolle Reranking hat nichts bewertet; die fusionierte Reihenfolge blieb erhalten und wurde auf top-k ${m[1]} gekappt.`,
  },
  {
    pattern: /^rerank candidate window (\d+)$/,
    german: (m) => `Kandidatenfenster der Neubewertung: ${m[1]}.`,
  },
  {
    pattern: /^(\d+) of (\d+) candidate\(s\) scored by the rerank model$/,
    german: (m) => `${m[1]} von ${m[2]} Kandidaten vom Reranking-Modell bewertet.`,
  },

  // DocumentCompletionStage
  {
    pattern: /^max-chunks-per-document (\d+)$/,
    german: (m) => `max-chunks-per-document ${m[1]} (Abschnitte je Dokument).`,
  },
  {
    pattern: /^(\d+) sibling chunk\(s\) completed from the candidate pool$/,
    german: (m) =>
      `${plural(m[1], 'weiterer Abschnitt', 'weitere Abschnitte')} aus dem Kandidatenvorrat ergänzt.`,
  },

  // StageStatus - the generic note of a stage that did not run
  {
    pattern: /^stage switched off for this run$/,
    german: () => 'Stufe für diesen Lauf abgeschaltet.',
  },
  {
    pattern: /^stage switched on but unavailable: the run continued without it$/,
    german: () => 'Stufe eingeschaltet, aber nicht verfügbar: Der Lauf wurde ohne sie fortgesetzt.',
  },
  {
    pattern: /^run halted before this stage: nothing left to retrieve$/,
    german: () => 'Der Lauf endete vor dieser Stufe: Es war nichts mehr zu holen.',
  },
]

const LIST_LABEL_RULES: NoteRule[] = [
  {
    pattern: /^vector search · sub-query (\d+)$/,
    german: (m) => `Vektorsuche · Teilfrage ${m[1]}`,
  },
  {
    pattern: /^full-text search · sub-query (\d+)$/,
    german: (m) => `Volltextsuche · Teilfrage ${m[1]}`,
  },
  { pattern: /^fused \(RRF\)$/, german: () => 'fusioniert (RRF)' },
]

function translate(rules: NoteRule[], text: string): string {
  for (const rule of rules) {
    const match = text.match(rule.pattern)
    if (match) {
      return rule.german(match)
    }
  }
  // A note this layer does not know is shown as it came: a newly added backend note belongs in
  // front of the operator in English rather than being silently swallowed.
  return text
}

/** The German wording of one stage note. */
export function translateStageNote(note: string): string {
  return translate(NOTE_RULES, note)
}

/**
 * The German wording of one candidate list label. A missing label means the fused list of a
 * completed run, so it gets the same wording as the explicit fused label - one name per list.
 */
export function translateListLabel(label: string | null | undefined): string {
  return label == null ? 'fusioniert (RRF)' : translate(LIST_LABEL_RULES, label)
}
