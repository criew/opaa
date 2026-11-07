import type { ReactNode } from 'react'

type GreetingRenderer = (greeting: ReactNode) => ReactNode

let registered: GreetingRenderer | null = null

/**
 * Lets the app shell decorate the greeting of an empty chat (the first-steps checklist of the UI
 * lab for #2312) without the chat theme importing the shell. Registered once at module load.
 */
export function registerEmptyChatGreeting(render: GreetingRenderer): void {
  registered = render
}

/** The chat's own greeting, decorated by the registered renderer if there is one. */
export function renderEmptyChatGreeting(greeting: ReactNode): ReactNode {
  return registered ? registered(greeting) : greeting
}
