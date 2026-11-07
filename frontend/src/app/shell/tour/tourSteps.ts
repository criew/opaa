/**
 * The stations of the first-login tour (UI lab #2312). Each station names the element it explains.
 * On narrow screens rail and space column sit in the drawer; their stations collapse into one
 * station at the menu button, so the tour never points at the same element twice in a row.
 */
export interface TourStep {
  id: string
  title: string
  text: string
  /** Finds the element the station explains. */
  anchor: () => HTMLElement | null
  /** The card goes above the anchor and also above this element, which the next station shows. */
  keepVisible?: () => HTMLElement | null
  /** Shows a copy of an answer with footnote marks; the tour needs no sample chat for it. */
  withAnswerSample?: boolean
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

const ASK: TourStep = {
  id: 'ask',
  title: 'Fragen stellen',
  text: 'Fragen Sie in eigenen Worten. Jede Antwort nennt ihre Fundstellen als Ziffern; ein Klick zeigt die Stelle im Dokument.',
  anchor: chatInput,
  keepVisible: spaceKnowledgeChip,
  withAnswerSample: true,
}

const KNOWLEDGE: TourStep = {
  id: 'knowledge',
  title: 'Worauf die Antwort sich stützt',
  text: '@Space-Wissen ist das Wissen, das diesem Space zugeordnet ist und das Sie lesen dürfen. Mit @ wählen Sie einzelne Bibliotheken.',
  anchor: spaceKnowledgeChip,
  keepVisible: spaceKnowledgeChip,
}

const ADMIN_SENTENCE =
  'Unter „Admin“ verwalten Sie Konten, Gruppen und Modelle; diesen Eintrag sehen nur Personen in der Systemverwaltung.'

function wideSteps(isAdmin: boolean): TourStep[] {
  const admin: TourStep[] = isAdmin
    ? [
        {
          id: 'admin',
          title: 'Systemverwaltung',
          text: ADMIN_SENTENCE,
          anchor: railLink('/admin/users'),
        },
      ]
    : []
  return [
    ASK,
    KNOWLEDGE,
    {
      id: 'spaces',
      title: 'Privat oder im Team',
      text: 'Im privaten Bereich arbeiten Sie allein. Ein Space teilt Chats und Wissen mit seinen Mitgliedern.',
      anchor: byLabel('Spaces'),
    },
    {
      id: 'catalog',
      title: 'Wissen finden',
      text: 'Der Katalog zeigt alle Bibliotheken und Prompts, die Sie lesen dürfen. Von dort ordnen Sie sie einem Space zu.',
      anchor: railLink('/catalog'),
    },
    ...admin,
    {
      id: 'again',
      title: 'Rundgang wiederholen',
      text: 'Über Ihr Profil starten Sie diesen Rundgang jederzeit erneut.',
      anchor: byLabel('Profil und Einstellungen'),
    },
  ]
}

function narrowSteps(isAdmin: boolean): TourStep[] {
  const menuText = [
    'Im Menü wechseln Sie zwischen Ihrem privaten Bereich und Spaces, finden im Katalog alle Bibliotheken und Prompts und starten über Ihr Profil diesen Rundgang erneut.',
    ...(isAdmin ? [ADMIN_SENTENCE] : []),
  ].join(' ')
  return [
    ASK,
    KNOWLEDGE,
    { id: 'menu', title: 'Alles Weitere im Menü', text: menuText, anchor: byLabel('Menü öffnen') },
  ]
}

export function stepsFor(isAdmin: boolean, isDesktop: boolean): TourStep[] {
  return isDesktop ? wideSteps(isAdmin) : narrowSteps(isAdmin)
}

/** Puts the caret into the chat input, where every way out of the tour ends. */
export function focusChatInput(): void {
  document.querySelector<HTMLElement>('[aria-label="Nachricht eingeben"]')?.focus()
}
