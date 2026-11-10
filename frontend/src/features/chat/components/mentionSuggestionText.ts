import type { MentionSuggestion } from './chatInputText'

/** The suggestions in the two groups of the '@' menu, knowledge first, each with its list index. */
export function mentionGroups(suggestions: MentionSuggestion[]) {
  const groups: {
    kind: 'knowledge' | 'agent'
    items: { suggestion: MentionSuggestion; index: number }[]
  }[] = []
  suggestions.forEach((suggestion, index) => {
    const kind = suggestion.kind === 'agent' ? 'agent' : 'knowledge'
    const last = groups.at(-1)
    if (last && last.kind === kind) last.items.push({ suggestion, index })
    else groups.push({ kind, items: [{ suggestion, index }] })
  })
  return groups
}

export function mentionKey(suggestion: MentionSuggestion): string {
  if (suggestion.kind === 'all') return '@all-knowledge'
  return suggestion.kind === 'agent' ? `agent-${suggestion.agent.id}` : suggestion.library.id
}

export function mentionName(suggestion: MentionSuggestion): string {
  if (suggestion.kind === 'all') return '@Space-Wissen'
  return suggestion.kind === 'agent' ? suggestion.agent.name : suggestion.library.name
}

export function mentionBadge(suggestion: MentionSuggestion): string {
  if (suggestion.kind === 'all') return 'Wissen des Space · hebt Eingrenzung auf'
  return suggestion.kind === 'agent'
    ? 'Agent · ersetzt den Suchbereich'
    : 'Bibliothek · verengt die Suche'
}
