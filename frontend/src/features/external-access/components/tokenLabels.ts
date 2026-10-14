import type { ExternalAccessTokenStatus } from '@/shared/api/types'

/** A token's state in words - the same four as in the backend (ADR-0035). */
export const TOKEN_STATUS_LABEL: Record<ExternalAccessTokenStatus, string> = {
  ACTIVE: 'gültig',
  EXPIRED: 'abgelaufen',
  REVOKED: 'widerrufen',
  BLOCKED: 'gesperrt',
}

/** From when the own list points to the upcoming expiry - like the first reminder email. */
export const EXPIRY_WARNING_DAYS = 14

export const DISCLOSURE_HINT =
  'Ihre Fragen und die abgerufenen Inhalte verlassen mit diesem Token OPAA und unterliegen der ' +
  'Protokollierung des fremden Werkzeugs. Die Zusage, dass OPAA einzelne Abfragen nicht ' +
  'mitschreibt, gilt dort nicht. Name, Bibliotheken und Ablauf dieses Tokens sieht auch die ' +
  'Systemverwaltung.'

export const SELECTION_IS_FINAL_HINT =
  'Nur freigegebene Bibliotheken sind wählbar. Die Auswahl lässt sich später nicht ändern - eine ' +
  'Änderung ist ein neues Token.'

export const SHOWN_ONCE_HINT =
  'Dieser Wert wird nur jetzt angezeigt. Nach dem Schließen lässt er sich nicht erneut anzeigen. ' +
  'Legen Sie ihn nicht in einem Repository und nicht in einem synchronisierten Profil ab.'

export const CHANNEL_OFF_HINT =
  'Fremdzugänge sind für diese Installation abgeschaltet. Vorhandene Tokens wirken nicht, bleiben ' +
  'aber erhalten und lassen sich weiterhin widerrufen. Ein neues Token kann erst angelegt werden, ' +
  'wenn Ihre Systemverwaltung den Kanal wieder öffnet.'

export const SUSPENDED_LIBRARY_HINT =
  'Die Freigabe wurde zurückgenommen oder ist abgelaufen. Die Bibliothek wirkt in diesem Token ' +
  'nicht mehr und lebt auch bei einer erneuten Freigabe nicht wieder auf - dafür ist ein neues ' +
  'Token nötig.'

export const NO_LIBRARIES_HINT =
  'Derzeit ist keine Bibliothek für Fremdzugänge wählbar: Entweder haben Sie auf die betreffenden ' +
  'Bibliotheken keinen Lesezugriff, oder sie sind nicht für Fremdzugänge freigegeben. Beides ' +
  'entscheidet, wer die jeweilige Bibliothek verwaltet - sprechen Sie diese Verwaltung an. Einen ' +
  'Antragsweg in der Anwendung gibt es dafür bewusst nicht.'

export const ADMIN_PURPOSE_HINT =
  'Bestandsliste für die Rechteprüfung und für Sperren. Nutzungsangaben je Person gibt es hier ' +
  'nicht, und es wird nicht nach Person gefiltert.'

export const REVOKE_CONSEQUENCE =
  'Das Werkzeug verliert sofort den Zugriff - schon der nächste Aufruf wird abgewiesen. Der Wert ' +
  'lässt sich nicht wieder in Kraft setzen; ein neuer Zugang ist ein neues Token.'

export const BLOCK_CONSEQUENCE =
  'Das Token wirkt ab dem nächsten Aufruf nicht mehr. Die Person kann sich ein neues erzeugen, ' +
  'solange der Kanal offen ist und ihr Konto besteht.'

export const BLOCK_ALL_CONSEQUENCE =
  'Alle noch wirksamen Tokens dieser Person werden gesperrt - der Hebel für einen Vorfall oder ein ' +
  'ausscheidendes Konto. Bereits abgelaufene oder widerrufene Tokens bleiben, wie sie sind.'

/** A point in time as a date - this channel never shows a time of day. */
export function formatDate(value: string | undefined | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/** Whole days from today until the expiry; negative when it has passed. */
export function daysUntil(expiresAt: string, now: Date = new Date()): number {
  const expiry = new Date(expiresAt)
  if (Number.isNaN(expiry.getTime())) return Number.POSITIVE_INFINITY
  return Math.ceil((expiry.getTime() - now.getTime()) / 86_400_000)
}

/**
 * The hint about the upcoming expiry in the own list - it does not replace the reminder email
 * but stands where the person can act.
 */
export function expiryWarning(
  status: ExternalAccessTokenStatus,
  expiresAt: string,
  now: Date = new Date(),
): string | null {
  if (status !== 'ACTIVE') return null
  const days = daysUntil(expiresAt, now)
  if (days > EXPIRY_WARNING_DAYS) return null
  if (days <= 0) return 'Läuft heute ab'
  if (days === 1) return 'Läuft morgen ab'
  return `Läuft in ${days} Tagen ab`
}

/** Distance to the upper bound when capping - see {@link expiryInstantOf}. */
const CLOCK_SKEW_MARGIN_MS = 3_600_000

/** A date as `YYYY-MM-DD` in the device's time zone - the format of the date field. */
export function toDateInputValue(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/**
 * The earliest selectable expiry day: tomorrow. Today is valid until the end of the day, but an
 * access that ends on the day of issuance is no access - and the field only offers what the
 * validation next to it accepts as well.
 */
export function earliestExpiryDate(now: Date = new Date()): Date {
  return new Date(now.getTime() + 86_400_000)
}

/**
 * Default and upper bound of the expiry date: the installation's maximum lifetime, from now on.
 *
 * The calculation is in hours, not in calendar days - exactly like the interface's validation. A
 * period across a daylight saving day would otherwise be one hour too long and run into
 * its rejection.
 */
export function maxExpiryDate(tokenMaxLifetimeDays: number, now: Date = new Date()): Date {
  return new Date(now.getTime() + tokenMaxLifetimeDays * 86_400_000)
}

/**
 * The expiry date as a point in time for the interface: the end of the chosen day in the device's
 * zone. A token that dies at noon on the entered day would be a surprise.
 *
 * On the last selectable day it is cut to the maximum lifetime: the interface calculates in
 * hours from now, and that day's end would exceed it by the time of day already passed.
 *
 * It is cut to the upper bound **minus one hour**. The interface checks against its
 * own clock; if the device's clock runs ahead, the exactly hit upper bound would already be
 * exceeded there - of all days on the one the dialog itself proposes.
 */
export function expiryInstantOf(dateInputValue: string, latestAllowed: Date): string {
  const [year, month, day] = dateInputValue.split('-').map(Number)
  const endOfDay = new Date(year, month - 1, day, 23, 59, 59)
  const ceiling = latestAllowed.getTime() - CLOCK_SKEW_MARGIN_MS
  return new Date(Math.min(endOfDay.getTime(), ceiling)).toISOString()
}

/**
 * The chosen libraries whose release ends before the token expires - by name.
 *
 * The two periods run independently: a release that ends earlier removes the library from
 * the token, and it does not revive there even with a renewed release. That has to be visible
 * before creating, not only afterwards on the suspended row.
 */
export function releaseEndsBeforeExpiry(
  libraries: ReadonlyArray<{ id: string; name: string; releaseExpiresAt: string }>,
  selectedIds: ReadonlyArray<string>,
  expiresAtIso: string,
): string[] {
  const expiry = new Date(expiresAtIso).getTime()
  if (Number.isNaN(expiry)) return []
  return libraries
    .filter((library) => selectedIds.includes(library.id))
    .filter((library) => new Date(library.releaseExpiresAt).getTime() < expiry)
    .map((library) => library.name)
}

/**
 * The setup snippets for the common clients. They appear only in the one-time dialog: the
 * value is already there, and a snippet without it would be useless anywhere else. The
 * detailed instructions are the manual's business.
 *
 * Only the Claude Code command carries the value in plain text. `--scope user` stores the entry
 * across projects in the user configuration instead of per project; the shared storage
 * that would carry the value into version control would be `--scope project`. The two JSON
 * snippets belong in a file that can be shared or checked in anyway, and therefore refer
 * to an input (VS Code) or an environment variable (Cursor).
 */
export function clientSetupSnippets(baseUrl: string, token: string) {
  const url = `${baseUrl.replace(/\/+$/, '')}/mcp`
  return {
    url,
    claudeCode: `claude mcp add --scope user --transport http opaa ${url} --header "Authorization: Bearer ${token}"`,
    vsCode: JSON.stringify(
      {
        servers: {
          opaa: {
            type: 'http',
            url,
            headers: { Authorization: 'Bearer ${input:opaa-token}' },
          },
        },
        inputs: [
          {
            id: 'opaa-token',
            type: 'promptString',
            description: 'OPAA-Token',
            password: true,
          },
        ],
      },
      null,
      2,
    ),
    cursor: JSON.stringify(
      {
        mcpServers: {
          opaa: {
            url,
            headers: { Authorization: 'Bearer ${env:OPAA_TOKEN}' },
          },
        },
      },
      null,
      2,
    ),
  }
}
