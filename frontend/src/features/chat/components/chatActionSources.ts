import type { ChatActionSource } from './chatActions'
import { promptActionSource } from './promptActionSource'

/**
 * Every source of the chat input's '/' menu, in the order the menu lists them. A further kind of
 * action is one more entry here; the input and the menu stay as they are.
 */
export const CHAT_ACTION_SOURCES: readonly ChatActionSource[] = [promptActionSource]
