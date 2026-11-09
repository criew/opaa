import type { AgentConfig, DocumentTemplate } from './types'

function templateNames(config: AgentConfig, templates: DocumentTemplate[]): string {
  return config.templateIds
    .map((id) => templates.find((template) => template.id === id)?.name ?? id)
    .map((name) => `„${name}“`)
    .join(', ')
}

export function outputSummary(config: AgentConfig, templates: DocumentTemplate[]): string {
  if (config.output === 'CHAT') return 'Antwort im Chat'
  const format = config.fileFormat === 'PDF' ? 'PDF' : 'Word'
  return `${format} nach ${templateNames(config, templates) || 'Vorlage'}`
}

/** What a run of the agent will do, step by step, in plain words. */
export function runOutline(
  config: AgentConfig,
  libraryCount: number,
  templates: DocumentTemplate[],
) {
  const steps: string[] = []
  if (config.planStep === 'CONFIRM') steps.push('Zeigt seinen Plan und wartet auf Ihr „Los“.')
  if (config.planStep === 'SHOW') steps.push('Zeigt seinen Plan über der Antwort.')
  if (config.tools.includes('list_sources')) steps.push('Sieht nach, welche Dokumente es gibt.')
  if (config.tools.includes('search_knowledge'))
    steps.push(
      libraryCount === 0
        ? 'Sucht in allem, was Sie lesen dürfen.'
        : `Sucht in ${libraryCount === 1 ? '1 Bibliothek' : `${libraryCount} Bibliotheken`}.`,
    )
  if (config.tools.includes('read_document')) steps.push('Liest wichtige Stellen im Zusammenhang.')
  if (config.tools.includes('run_code')) steps.push('Rechnet nach, wo Zahlen vorkommen.')
  if (config.tools.includes('ask_user')) steps.push('Fragt nach, wenn etwas unklar ist.')
  if (config.namesContradictions)
    steps.push('Stellt Widersprüche mit beiden Quellen nebeneinander.')
  steps.push(
    config.output === 'FILE'
      ? `Legt das Ergebnis als ${outputSummary(config, templates)} ab.`
      : 'Antwortet im Chat mit Fundstellen.',
  )
  return steps
}
