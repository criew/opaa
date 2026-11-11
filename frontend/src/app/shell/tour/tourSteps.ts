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
  /**
   * The element exists only in some installations or situations (a feature switched off, no
   * transcription model): the station is shown only while its element is on screen.
   */
  optional?: boolean
}

export interface TourAudience {
  isSystemAdmin: boolean
  /** At least 900 px wide: rail and space column are on screen, not in the drawer. */
  isWide: boolean
  /** The anchors of optional stations that are on screen right now. */
  present: ReadonlySet<TourAnchorName>
}

const ASK: TourStep = {
  id: 'ask',
  title: 'Fragen stellen',
  text: 'Schreiben Sie Ihre Frage hier hinein, so wie Sie einen Menschen fragen würden, und schicken Sie sie mit Enter ab. Im selben Chat können Sie einfach weiterfragen.',
  anchor: 'chat-input',
  keepVisible: 'space-knowledge',
}

const CHECK: TourStep = {
  id: 'check',
  title: 'Antworten prüfen',
  text: 'In jeder Antwort stehen kleine Zahlen. Jede zeigt, aus welchem Dokument die Information stammt; ein Klick öffnet genau diese Stelle. Die Antworten schreibt eine KI, und die kann sich irren – prüfen Sie Wichtiges deshalb in der Quelle.',
  anchor: 'ai-notice',
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

const FILTER: TourStep = {
  id: 'filter',
  title: 'Suche eingrenzen',
  text: 'Mit „Filter“ sucht OPAA nur in bestimmten Dokumenten – zum Beispiel nur in einer Dokumentart oder nur in Dokumenten aus einem bestimmten Zeitraum.',
  anchor: 'filter',
  optional: true,
}

const PROMPTS: TourStep = {
  id: 'prompts',
  title: 'Fertige Fragen nutzen',
  text: 'Tippen Sie am Anfang einer Zeile einen Schrägstrich /. Dann sehen Sie Prompts: fertige Fragen und Anweisungen, die für Ihre Arbeit vorbereitet wurden. Ein Klick setzt sie ein.',
  anchor: 'prompts',
  optional: true,
}

const ATTACH: TourStep = {
  id: 'attach',
  title: 'Eigene Dateien mitgeben',
  text: 'Mit der Büroklammer hängen Sie eine eigene Datei an den Chat. OPAA bezieht sie dann in die Antworten ein. Ihre Anhänge sehen nur Sie, und sie werden mit dem Chat gelöscht.',
  anchor: 'attach',
  optional: true,
}

const DICTATE: TourStep = {
  id: 'dictate',
  title: 'Frage sprechen',
  text: 'Mit dem Mikrofon sprechen Sie Ihre Frage, statt sie zu tippen. Mit dem Haken landet der Text im Eingabefeld, wo Sie ihn noch verbessern können; mit „Senden“ geht die Frage gleich ab. Die Aufnahme wird nicht gespeichert.',
  anchor: 'dictate',
  optional: true,
}

const NEW_CHAT: TourStep = {
  id: 'new-chat',
  title: 'Neues Thema, neuer Chat',
  text: 'Für ein neues Thema starten Sie mit dem Plus einen neuen Chat. So bleiben Ihre Gespräche übersichtlich.',
  anchor: 'new-chat',
}

const CHATS: TourStep = {
  id: 'chats',
  title: 'Chats wiederfinden',
  text: 'Hier stehen Ihre letzten Chats. Die Lupe durchsucht alle Ihre Chats, auch die Fragen und Antworten darin. Ihre Chats sieht niemand außer Ihnen.',
  anchor: 'chats',
}

const SPACES: TourStep = {
  id: 'spaces',
  title: 'Privat oder im Team',
  text: 'Ihren privaten Bereich nutzen nur Sie. Ein Space ist ein gemeinsamer Arbeitsraum für ein Team: Alle Mitglieder fragen in denselben Bibliotheken und nutzen dieselben Prompts. Ihre Chats sehen trotzdem nur Sie.',
  anchor: 'spaces',
}

const CATALOG: TourStep = {
  id: 'catalog',
  title: 'Wissen finden',
  text: 'Im Katalog finden Sie alle Bibliotheken, die Sie lesen dürfen, und alle Prompts – das sind fertige Vorlagen für Fragen. Von hier aus fügen Sie sie einem Space hinzu.',
  anchor: 'catalog',
}

const HANDBOOK: TourStep = {
  id: 'handbook',
  title: 'Hilfe zur aktuellen Seite',
  text: 'Das Fragezeichen öffnet das Handbuch, und zwar die Seite, die zu dem passt, was Sie gerade vor sich haben.',
  anchor: 'handbook',
  optional: true,
}

const NOTIFICATIONS: TourStep = {
  id: 'notifications',
  title: 'Benachrichtigungen',
  text: 'Die Glocke meldet, wenn sich für Sie etwas ändert – zum Beispiel, wenn ein Zugang, den Sie eingerichtet haben, bald abläuft. Die Zahl daran zeigt, wie viele Meldungen Sie noch nicht gelesen haben.',
  anchor: 'notifications',
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
  text: 'Über Ihr Profil erreichen Sie Ihre Einstellungen. Dort starten Sie auch diesen Rundgang jederzeit noch einmal: Klicken Sie auf Ihr Profil und dann auf „Rundgang starten“.',
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
    text: `Hinter diesem Knopf finden Sie Ihren privaten Bereich, die Spaces Ihrer Teams, Ihre Chats und den Katalog mit Bibliotheken und Prompts${admin} – und über Ihr Profil starten Sie diesen Rundgang noch einmal.`,
    anchor: 'menu',
  }
}

/** The stations around the question field, the same at every width. */
const AT_THE_INPUT: TourStep[] = [ASK, CHECK, KNOWLEDGE, FILTER, PROMPTS, ATTACH, DICTATE]

/**
 * The stations for this person, width and screen, in order: the admin station only for that role,
 * an optional station only while its element is on screen.
 */
export function tourStepsFor({ isSystemAdmin, isWide, present }: TourAudience): TourStep[] {
  const all = isWide
    ? [
        ...AT_THE_INPUT,
        NEW_CHAT,
        CHATS,
        SPACES,
        CATALOG,
        HANDBOOK,
        NOTIFICATIONS,
        ...(isSystemAdmin ? [ADMIN] : []),
        AGAIN,
      ]
    : [...AT_THE_INPUT, NOTIFICATIONS, menuStep(isSystemAdmin)]
  return all.filter((step) => !step.optional || present.has(step.anchor))
}

/** The anchors whose stations depend on what is on screen. */
export const OPTIONAL_TOUR_ANCHORS: readonly TourAnchorName[] = [
  FILTER,
  PROMPTS,
  ATTACH,
  DICTATE,
  HANDBOOK,
].map((step) => step.anchor)
