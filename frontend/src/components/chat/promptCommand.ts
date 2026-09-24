import type { AvailablePrompt } from '../../types/api'

export interface ActiveSlashCommand {
  /** Index of the '/' that opened the command - always the start of a line. */
  start: number
  /** Text typed after '/', used to filter the prompts. */
  query: string
}

/**
 * Finds a slash command being typed at the cursor: a '/' at the very start of the cursor's line,
 * followed by no whitespace. Anywhere else a '/' is plain text.
 */
export function findActiveSlashCommand(text: string, cursor: number): ActiveSlashCommand | null {
  const lineStart = text.lastIndexOf('\n', cursor - 1) + 1
  const fragment = text.slice(lineStart, cursor)
  if (!/^\/\S*$/.test(fragment)) return null
  return { start: lineStart, query: fragment.slice(1) }
}

/** The prompts matching `query` in command name, title or description, in the given order. */
export function matchPrompts(prompts: AvailablePrompt[], query: string): AvailablePrompt[] {
  const needle = query.toLowerCase()
  if (!needle) return prompts
  return prompts.filter(
    (prompt) =>
      prompt.name.toLowerCase().includes(needle) ||
      prompt.title.toLowerCase().includes(needle) ||
      (prompt.description ?? '').toLowerCase().includes(needle),
  )
}

/** The id of the '/' option at `index` - the input's `aria-activedescendant` points at it. */
export function promptOptionId(listboxId: string, index: number): string {
  return `${listboxId}-option-${index}`
}
