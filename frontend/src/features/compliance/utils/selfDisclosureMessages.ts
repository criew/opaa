import type { SelfDisclosureStatus } from '@/shared/api/types'

/** The hint against compelled disclosure, word for word as the specification fixes it. */
export const PERSONAL_ONLY_NOTICE =
  'Diese Auskunft ist nur für Sie. Niemand darf verlangen, dass Sie sie vorlegen oder weitergeben.'

/** Shown instead of the button while the system administration has switched the feature off. */
export const DISABLED_MESSAGE =
  'Die Selbstauskunft ist in dieser Installation derzeit abgeschaltet. Ihr Recht auf Auskunft ' +
  'nach Art. 15 DSGVO bleibt davon unberührt: Sie können die Auskunft bei Ihrer Behörde ' +
  'beantragen, in der Regel über die oder den Datenschutzbeauftragten.'

export const CAPACITY_MESSAGE =
  'Gerade laden viele Personen ihre Daten herunter. Bitte versuchen Sie es in einer Minute erneut.'

export const UNREACHABLE_MESSAGE =
  'Der Server ist gerade nicht erreichbar. Bitte prüfen Sie Ihre Verbindung und versuchen Sie es erneut.'

export const NOT_STARTED_MESSAGE =
  'Der Download ließ sich nicht starten. Bitte versuchen Sie es erneut.'

export const ABORTED_MESSAGE = 'Der Download wurde abgebrochen und nicht gezählt.'

export const LAST_ABORTED_MESSAGE = 'Der letzte Download wurde abgebrochen und nicht gezählt.'

export const RETRY_POSSIBLE = 'Sie können es erneut versuchen.'

export const COMPLETED_MESSAGE = 'Ihre Daten wurden vollständig heruntergeladen.'

/** A moment as „24.10.2026, 00:00 Uhr“, in the time zone of the browser. */
export function formatMoment(iso: string): string {
  const moment = new Date(iso)
  const date = moment.toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
  const time = moment.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })
  return `${date}, ${time} Uhr`
}

function from(status: SelfDisclosureStatus, what: string): string {
  return status.nextPossibleAt
    ? ` ${what} ist ab ${formatMoment(status.nextPossibleAt)} möglich.`
    : ''
}

/** The daily limit in words, for the explanation above the button. */
export function dailyLimitText(dailyLimit: number): string {
  const times =
    dailyLimit === 1 ? 'Einmal pro Tag möglich.' : `Bis zu ${dailyLimit}-mal pro Tag möglich.`
  return `${times} Ein abgebrochener Download zählt nicht.`
}

/** Why no export can start now, or `null` when one can. */
export function blockedText(status: SelfDisclosureStatus): string | null {
  switch (status.blocked) {
    case 'DISABLED':
      return DISABLED_MESSAGE
    case 'DAILY_LIMIT':
      return `Für heute ist kein weiterer Download möglich.${from(status, 'Der nächste')}`
    case 'STARTS_EXHAUSTED':
      return (
        'Starts für heute erschöpft: Der Download wurde heute zu oft abgebrochen.' +
        from(status, 'Der nächste Versuch')
      )
    case 'RUNNING_EXPORT':
      return 'Gerade läuft ein Download Ihrer Daten. Ein neuer ist möglich, sobald er beendet ist.'
    default:
      return null
  }
}

/** The outcome of an attempt that broke off, with whether a new one may start now. */
export function abortedText(status: SelfDisclosureStatus, lead: string): string {
  return status.retryPossible ? `${lead} ${RETRY_POSSIBLE}` : lead
}

/** The outcome of a completed attempt, with from when the next is possible once the day is used. */
export function completedText(status: SelfDisclosureStatus): string {
  return status.remainingExports === 0
    ? `${COMPLETED_MESSAGE}${from(status, 'Der nächste Download')}`
    : COMPLETED_MESSAGE
}
