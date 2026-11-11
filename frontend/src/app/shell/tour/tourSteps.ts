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
  text: 'Schreiben Sie Ihre Frage hier hinein, so wie Sie einen Menschen fragen würden. In der Antwort stehen kleine Zahlen: Jede zeigt, aus welchem Dokument die Information stammt. Ein Klick auf die Zahl öffnet genau diese Stelle.',
  anchor: 'chat-input',
  keepVisible: 'space-knowledge',
  withAnswerSample: true,
}

const KNOWLEDGE: TourStep = {
  id: 'knowledge',
  title: 'Worauf die Antwort sich stützt',
  text: 'OPAA sucht die Antwort in Dokumenten, die Sie lesen dürfen. „@Space-Wissen“ heißt: alle Dokumente, die zu diesem Space gehören. Tippen Sie @, wenn nur bestimmte Bibliotheken – also Sammlungen von Dokumenten – gelten sollen.',
  anchor: 'space-knowledge',
  // Above the chip, so the card leaves the question field below it free.
  keepVisible: 'space-knowledge',
}

const SPACES: TourStep = {
  id: 'spaces',
  title: 'Privat oder im Team',
  text: 'Ihren privaten Bereich sehen nur Sie. Ein Space ist wie ein gemeinsamer Ordner für ein Team: Alle Mitglieder sehen dieselben Chats und Dokumente.',
  anchor: 'spaces',
}

const CATALOG: TourStep = {
  id: 'catalog',
  title: 'Wissen finden',
  text: 'Im Katalog finden Sie alle Bibliotheken, die Sie lesen dürfen, und alle Prompts – das sind fertige Vorlagen für Fragen. Von hier aus fügen Sie sie einem Space hinzu.',
  anchor: 'catalog',
}

const ADMIN: TourStep = {
  id: 'admin',
  title: 'Systemverwaltung',
  text: 'Unter „Admin“ richten Sie OPAA für alle ein: Konten, Gruppen und die KI-Modelle. Diesen Eintrag sehen nur Personen aus der Systemverwaltung.',
  anchor: 'admin',
}

const AGAIN: TourStep = {
  id: 'again',
  title: 'Rundgang wiederholen',
  text: 'Sie möchten den Rundgang noch einmal sehen? Klicken Sie auf Ihr Profil und dann auf „Rundgang starten“.',
  anchor: 'profile',
}

/** Below 900 px rail and space column sit in the drawer: one station at its button names them. */
function menuStep(isSystemAdmin: boolean): TourStep {
  const admin = isSystemAdmin
    ? ', unter „Admin“ die Verwaltung von Konten, Gruppen und KI-Modellen'
    : ''
  return {
    id: 'menu',
    title: 'Alles Weitere im Menü',
    text: `Hinter diesem Knopf finden Sie Ihren privaten Bereich, die Spaces Ihrer Teams und den Katalog mit Bibliotheken und Prompts${admin} – und über Ihr Profil starten Sie diesen Rundgang noch einmal.`,
    anchor: 'menu',
  }
}

/** The stations for this person and width, in order; the admin station only for that role. */
export function tourStepsFor({ isSystemAdmin, isWide }: TourAudience): TourStep[] {
  if (!isWide) return [ASK, KNOWLEDGE, menuStep(isSystemAdmin)]
  return [ASK, KNOWLEDGE, SPACES, CATALOG, ...(isSystemAdmin ? [ADMIN] : []), AGAIN]
}
