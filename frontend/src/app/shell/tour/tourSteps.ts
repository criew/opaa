import type { TourAnchorName } from '@/shared/ui/a11y/tourAnchor'

/** One station of the quick tour: the element it explains and what the card says about it. */
export interface TourStep {
  id: string
  title: string
  text: string
  anchor: TourAnchorName
  /** The card stays above this element too, because the next station shows it. */
  keepVisible?: TourAnchorName
  /** Shows a fixed miniature of an answer with footnote marks; the tour needs no sample chat. */
  withAnswerSample?: boolean
}

export interface TourAudience {
  isSystemAdmin: boolean
  /** At least 900 px wide: rail and space column are on screen, not in the drawer. */
  isWide: boolean
}

const ASK: TourStep = {
  id: 'ask',
  title: 'Fragen stellen',
  text: 'Fragen Sie in eigenen Worten. Jede Antwort nennt ihre Fundstellen als Ziffern; ein Klick zeigt die Stelle im Dokument.',
  anchor: 'chat-input',
  keepVisible: 'space-knowledge',
  withAnswerSample: true,
}

const KNOWLEDGE: TourStep = {
  id: 'knowledge',
  title: 'Worauf die Antwort sich stützt',
  text: '@Space-Wissen ist das Wissen, das diesem Space zugeordnet ist und das Sie lesen dürfen. Mit @ wählen Sie einzelne Bibliotheken.',
  anchor: 'space-knowledge',
  // Above the chip, so the card leaves the question field below it free.
  keepVisible: 'space-knowledge',
}

const SPACES: TourStep = {
  id: 'spaces',
  title: 'Privat oder im Team',
  text: 'Im privaten Bereich arbeiten Sie allein. Ein Space teilt Chats und Wissen mit seinen Mitgliedern.',
  anchor: 'spaces',
}

const CATALOG: TourStep = {
  id: 'catalog',
  title: 'Wissen finden',
  text: 'Der Katalog zeigt alle Bibliotheken und Prompts, die Sie lesen dürfen. Von dort ordnen Sie sie einem Space zu.',
  anchor: 'catalog',
}

const ADMIN: TourStep = {
  id: 'admin',
  title: 'Systemverwaltung',
  text: 'Unter „Admin“ verwalten Sie Konten, Gruppen und Modelle; diesen Eintrag sehen nur Personen in der Systemverwaltung.',
  anchor: 'admin',
}

const AGAIN: TourStep = {
  id: 'again',
  title: 'Rundgang wiederholen',
  text: 'Über Ihr Profil starten Sie diesen Rundgang jederzeit erneut.',
  anchor: 'profile',
}

/** Below 900 px rail and space column sit in the drawer: one station at its button names them. */
function menuStep(isSystemAdmin: boolean): TourStep {
  const admin = isSystemAdmin ? ', verwalten unter „Admin“ Konten, Gruppen und Modelle' : ''
  return {
    id: 'menu',
    title: 'Alles Weitere im Menü',
    text: `Im Menü wechseln Sie zwischen Ihrem privaten Bereich und Spaces, finden im Katalog alle Bibliotheken und Prompts${admin} und starten über Ihr Profil diesen Rundgang erneut.`,
    anchor: 'menu',
  }
}

/** The stations for this person and width, in order; the admin station only for that role. */
export function tourStepsFor({ isSystemAdmin, isWide }: TourAudience): TourStep[] {
  if (!isWide) return [ASK, KNOWLEDGE, menuStep(isSystemAdmin)]
  return [ASK, KNOWLEDGE, SPACES, CATALOG, ...(isSystemAdmin ? [ADMIN] : []), AGAIN]
}
