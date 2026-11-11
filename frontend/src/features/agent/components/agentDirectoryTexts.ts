import type { AgentDirectoryResponsibility } from '@/shared/api/types'

export const AGENT_DIRECTORY_ROUTE = '/agent-directory'

export function agentDirectoryAgentRoute(agentId: string): string {
  return `${AGENT_DIRECTORY_ROUTE}/agents/${agentId}`
}

export function agentDirectoryTemplateRoute(templateId: string): string {
  return `${AGENT_DIRECTORY_ROUTE}/templates/${templateId}`
}

/** „Referat 12 · Datenschutzbeauftragte" or „fehlt", as in the list and the CSV export. */
export function responsibilityLabel(responsibility: AgentDirectoryResponsibility): string {
  const named = [responsibility.groupName, responsibility.function]
    .filter((part): part is string => Boolean(part))
    .join(' · ')
  if (!responsibility.missing) return named
  return named ? `${named} (fehlt)` : 'fehlt'
}

export const FORBIDDEN_NOTICE =
  'Das Agentenverzeichnis sehen nur Personen mit dem Recht „Agentenverzeichnis einsehen“. Dieses Recht vergibt die Systemverwaltung.'

export const UNAVAILABLE_NOTICE =
  'Agenten sind in dieser Installation ausgeschaltet; ein Verzeichnis gibt es deshalb nicht.'
