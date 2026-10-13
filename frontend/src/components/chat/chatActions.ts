import type { ReactNode } from 'react'
import type { SvgIconComponent } from '@mui/icons-material'
import type { FeatureName, FeatureFlags } from '../../types/auth'
import { featureEnabledIn } from '../../stores/authStore'

/** One entry of the '/' menu. */
export interface ChatActionOption {
  /** Unique within its source. */
  id: string
  /** The heading the entry stands under, e.g. its prompt library. */
  group: { id: string; label: string }
  /** What follows the '/' - shown in monospace and matched against what is typed. */
  command: string
  title: string
  description?: string | null
  /** A short label at the end of the entry, e.g. "mit Formular". */
  badge?: string | null
}

/** What a source knows about the input it serves. */
export interface ChatActionContext {
  /** The chat's space; without one, a source offers nothing that belongs to a space. */
  spaceId: string | null
  /** The person's name, for texts that address or sign with it. */
  userName: string
}

/**
 * The mark of the text an action built - shown as a removable chip. Only a source that declares
 * {@link ChatActionSource.sendsMarkAsUsedPrompt} hands it to `onSend`.
 */
export interface ChatActionMark {
  id: string
  title: string
}

export interface ChatActionFormHandlers {
  /** Receives the finished text; it replaces the '/' fragment, sending stays separate. */
  onInsert: (text: string) => void
  onCancel: () => void
}

/** What choosing an entry yields: text for the input at once, or a form that produces it. */
export type ChatActionResult =
  | { kind: 'insert'; text: string; mark?: ChatActionMark }
  | {
      kind: 'form'
      mark?: ChatActionMark
      renderForm: (handlers: ChatActionFormHandlers) => ReactNode
    }

/** The words a source uses in the menu; with several sources the menu uses generic ones. */
export interface ChatActionTexts {
  heading: string
  /** The accessible name of the list. */
  listLabel: string
  loading: string
  /** Nothing to offer at all in this context. */
  none: string
  noMatch: string
  loadFailed: string
  chooseFailed: string
}

/**
 * Something that contributes entries to the '/' menu. A new kind of action is one more source in
 * `CHAT_ACTION_SOURCES` (chatActionSources.ts); the chat input itself does not change. A source behind an
 * installation-wide feature (ADR-0044) contributes nothing while it is switched off.
 */
export interface ChatActionSource {
  id: string
  feature?: FeatureName
  Icon: SvgIconComponent
  texts: ChatActionTexts
  /** The entries on offer now; loaded on every opening of the menu. */
  load: (context: ChatActionContext) => Promise<ChatActionOption[]>
  choose: (option: ChatActionOption, context: ChatActionContext) => Promise<ChatActionResult>
  /** The chip text for a mark this source set ("Prompt: Vermerk"). */
  markLabel: (mark: ChatActionMark) => string
  /**
   * Whether this source's mark is sent with the question as the prompt it was built from
   * (`usedPromptId`). A mark of any other source stays a chip in the input and is not sent.
   */
  sendsMarkAsUsedPrompt?: boolean
}

/** The menu's words while more than one source contributes. */
export const GENERIC_CHAT_ACTION_TEXTS: ChatActionTexts = {
  heading: 'Aktion einsetzen',
  listLabel: 'Aktionen',
  loading: 'Aktionen werden geladen …',
  none: 'In diesem Space stehen keine Aktionen zur Verfügung',
  noMatch: 'Keine passende Aktion gefunden',
  loadFailed: 'Aktionen konnten nicht geladen werden',
  chooseFailed: 'Die Aktion konnte nicht geladen werden',
}

/** The sources whose feature, if any, is switched on - in the order they are given. */
export function activeChatActionSources(
  sources: readonly ChatActionSource[],
  features: FeatureFlags,
): ChatActionSource[] {
  return sources.filter((source) => !source.feature || featureEnabledIn(features, source.feature))
}

/** The menu's words for the given active sources. */
export function chatActionTexts(sources: readonly ChatActionSource[]): ChatActionTexts {
  return sources.length === 1 ? sources[0].texts : GENERIC_CHAT_ACTION_TEXTS
}

/** The entries matching `query` in command, title or description, in the given order. */
export function matchChatActions<T extends ChatActionOption>(options: T[], query: string): T[] {
  const needle = query.toLowerCase()
  if (!needle) return options
  return options.filter(
    (option) =>
      option.command.toLowerCase().includes(needle) ||
      option.title.toLowerCase().includes(needle) ||
      (option.description ?? '').toLowerCase().includes(needle),
  )
}

/** The id of the '/' option at `index` - the input's `aria-activedescendant` points at it. */
export function actionOptionId(listboxId: string, index: number): string {
  return `${listboxId}-option-${index}`
}
