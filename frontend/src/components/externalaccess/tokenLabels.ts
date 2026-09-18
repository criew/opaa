import type { ExternalAccessTokenStatus } from '../../types/api'

/** Der Zustand eines Tokens in Worten - dieselben vier wie im Backend (ADR-0035). */
export const TOKEN_STATUS_LABEL: Record<ExternalAccessTokenStatus, string> = {
  ACTIVE: 'gültig',
  EXPIRED: 'abgelaufen',
  REVOKED: 'widerrufen',
  BLOCKED: 'gesperrt',
}

/** Ab wann die eigene Liste auf den bevorstehenden Ablauf hinweist - wie die erste Erinnerungsmail. */
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

/** Ein Zeitpunkt als Datum - dieser Kanal zeigt nie eine Uhrzeit. */
export function formatDate(value: string | undefined | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/** Ganze Tage von heute bis zum Ablauf; negativ, wenn er vorbei ist. */
export function daysUntil(expiresAt: string, now: Date = new Date()): number {
  const expiry = new Date(expiresAt)
  if (Number.isNaN(expiry.getTime())) return Number.POSITIVE_INFINITY
  return Math.ceil((expiry.getTime() - now.getTime()) / 86_400_000)
}

/**
 * Der Hinweis auf den bevorstehenden Ablauf in der eigenen Liste - er ersetzt die Erinnerungsmail
 * nicht, sondern steht dort, wo die Person handeln kann.
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

/** Abstand zur Obergrenze beim Kappen - siehe {@link expiryInstantOf}. */
const CLOCK_SKEW_MARGIN_MS = 3_600_000

/** Ein Datum als `YYYY-MM-DD` in der Zeitzone des Geräts - das Format des Datumsfeldes. */
export function toDateInputValue(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/**
 * Der früheste wählbare Ablauftag: morgen. Heute ist zwar bis zum Tagesende gültig, aber ein
 * Zugang, der noch am Ausstellungstag endet, ist kein Zugang - und das Feld bietet nur an, was die
 * Prüfung daneben auch annimmt.
 */
export function earliestExpiryDate(now: Date = new Date()): Date {
  return new Date(now.getTime() + 86_400_000)
}

/**
 * Vorgabe und Obergrenze des Ablaufdatums: die Höchstlaufzeit der Installation, von jetzt an.
 *
 * Gerechnet wird in Stunden, nicht in Kalendertagen - genau wie die Prüfung der Schnittstelle. Ein
 * Zeitraum über einen Zeitumstellungstag hinweg wäre sonst um eine Stunde zu lang und liefe in
 * deren Abweisung.
 */
export function maxExpiryDate(tokenMaxLifetimeDays: number, now: Date = new Date()): Date {
  return new Date(now.getTime() + tokenMaxLifetimeDays * 86_400_000)
}

/**
 * Das Ablaufdatum als Zeitpunkt für die Schnittstelle: das Ende des gewählten Tages in der Zone
 * des Geräts. Ein Token, das am eingegebenen Tag mittags stirbt, wäre eine Überraschung.
 *
 * Am letzten wählbaren Tag wird auf die Höchstlaufzeit gekürzt: Die Schnittstelle rechnet in
 * Stunden ab jetzt, und dessen Tagesende läge um die bereits vergangene Tageszeit darüber.
 *
 * Gekürzt wird auf die Obergrenze **minus einer Stunde**. Die Schnittstelle prüft gegen ihre
 * eigene Uhr; geht die des Geräts vor, wäre die exakt getroffene Obergrenze dort bereits
 * überschritten - ausgerechnet an dem Tag, den der Dialog selbst vorgibt.
 */
export function expiryInstantOf(dateInputValue: string, latestAllowed: Date): string {
  const [year, month, day] = dateInputValue.split('-').map(Number)
  const endOfDay = new Date(year, month - 1, day, 23, 59, 59)
  const ceiling = latestAllowed.getTime() - CLOCK_SKEW_MARGIN_MS
  return new Date(Math.min(endOfDay.getTime(), ceiling)).toISOString()
}

/**
 * Die gewählten Bibliotheken, deren Freigabe vor dem Ablauf des Tokens endet - nach Namen.
 *
 * Die beiden Fristen laufen unabhängig: Eine Freigabe, die früher endet, nimmt die Bibliothek aus
 * dem Token, und sie lebt dort auch bei einer erneuten Freigabe nicht wieder auf. Das ist vor dem
 * Erzeugen zu sehen, nicht erst hinterher an der ausgesetzten Zeile.
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
 * Die Einrichtungsschnipsel für die verbreiteten Clients. Sie stehen nur im Einmal-Dialog: Der
 * Wert steht dort schon, und ein Schnipsel ohne ihn wäre an jeder anderen Stelle nutzlos. Die
 * ausführliche Anleitung ist Sache des Handbuchs.
 *
 * Den Wert im Klartext trägt allein der Claude-Code-Befehl. `--scope user` legt den Eintrag dabei
 * projektübergreifend in der Benutzerkonfiguration ab statt projektbezogen; die geteilte Ablage,
 * die den Wert in die Versionsverwaltung trüge, wäre `--scope project`. Die beiden JSON-Schnipsel
 * gehören ohnehin in eine Datei, die geteilt oder eingecheckt werden kann, und verweisen deshalb
 * auf eine Eingabe (VS Code) beziehungsweise eine Umgebungsvariable (Cursor).
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
