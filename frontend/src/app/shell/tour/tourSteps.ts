/**
 * The stations of the first-login tour (UI lab #2312). Each station names the element it explains;
 * on narrow screens, where rail and space column sit in the drawer, stations of the drawer point
 * at the menu button instead and say so in their text.
 */
export interface TourStep {
  id: string
  title: string
  text: string
  /** Text on narrow screens, where the element lives behind the menu button. */
  narrowText?: string
  /** Finds the element the station explains on wide screens. */
  anchor: () => HTMLElement | null
  /** Whether the element sits in the drawer below the `md` breakpoint. */
  inDrawer?: boolean
  adminOnly?: boolean
}

const byLabel = (label: string) => () =>
  document.querySelector<HTMLElement>(`[aria-label="${label}"]`)

const railLink = (href: string) => () =>
  document.querySelector<HTMLElement>(`nav[aria-label="Globale Navigation"] a[href="${href}"]`)

/** The chat input's outer frame (the first ancestor with a border), not the bare textarea. */
function chatInput(): HTMLElement | null {
  const field = document.querySelector<HTMLElement>('[aria-label="Nachricht eingeben"]')
  for (
    let node = field?.parentElement;
    node && node.tagName !== 'MAIN';
    node = node.parentElement
  ) {
    if (parseFloat(getComputedStyle(node).borderTopWidth) >= 1) return node
  }
  return field
}

function spaceKnowledgeChip(): HTMLElement | null {
  const chips = document.querySelectorAll<HTMLElement>('main .MuiChip-root')
  return Array.from(chips).find((chip) => chip.textContent?.startsWith('@Space-Wissen')) ?? null
}

export const MENU_BUTTON = byLabel('Menü öffnen')

export const TOUR_STEPS: TourStep[] = [
  {
    id: 'ask',
    title: 'Fragen stellen',
    text: 'Fragen Sie in eigenen Worten. Jede Antwort nennt ihre Fundstellen als Ziffern; ein Klick zeigt die Stelle im Dokument.',
    anchor: chatInput,
  },
  {
    id: 'knowledge',
    title: 'Worauf die Antwort sich stützt',
    text: '@Space-Wissen ist das Wissen, das diesem Space zugeordnet ist und das Sie lesen dürfen. Mit @ wählen Sie einzelne Bibliotheken.',
    anchor: spaceKnowledgeChip,
  },
  {
    id: 'spaces',
    title: 'Privat oder im Team',
    text: 'Im privaten Bereich arbeiten Sie allein. Ein Space teilt Chats und Wissen mit seinen Mitgliedern.',
    narrowText:
      'Im Menü wechseln Sie zwischen dem privaten Bereich, in dem Sie allein arbeiten, und Spaces, die Chats und Wissen mit ihren Mitgliedern teilen.',
    anchor: byLabel('Spaces'),
    inDrawer: true,
  },
  {
    id: 'catalog',
    title: 'Wissen finden',
    text: 'Der Katalog zeigt alle Bibliotheken und Prompts, die Sie lesen dürfen. Von dort ordnen Sie sie einem Space zu.',
    narrowText:
      'Im Menü führt „Katalog“ zu allen Bibliotheken und Prompts, die Sie lesen dürfen. Von dort ordnen Sie sie einem Space zu.',
    anchor: railLink('/catalog'),
    inDrawer: true,
  },
  {
    id: 'admin',
    title: 'Systemverwaltung',
    text: 'Unter „Admin“ legen Sie Konten, Anbindungen und Modelle an. Diesen Eintrag sehen nur Personen in der Systemverwaltung.',
    narrowText:
      'Im Menü legen Sie unter „Admin“ Konten, Anbindungen und Modelle an. Diesen Eintrag sehen nur Personen in der Systemverwaltung.',
    anchor: railLink('/admin/users'),
    inDrawer: true,
    adminOnly: true,
  },
  {
    id: 'again',
    title: 'Rundgang wiederholen',
    text: 'Über Ihr Profil starten Sie diesen Rundgang jederzeit erneut.',
    narrowText: 'Im Menü starten Sie über Ihr Profil diesen Rundgang jederzeit erneut.',
    anchor: byLabel('Profil und Einstellungen'),
    inDrawer: true,
  },
]

export function stepsFor(isAdmin: boolean): TourStep[] {
  return TOUR_STEPS.filter((step) => !step.adminOnly || isAdmin)
}

/** Puts the caret into the chat input, the one action every variant ends with. */
export function focusChatInput(): void {
  document.querySelector<HTMLElement>('[aria-label="Nachricht eingeben"]')?.focus()
}
