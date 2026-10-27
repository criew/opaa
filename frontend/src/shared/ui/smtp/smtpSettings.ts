import type { MailEncryption } from '@/shared/api/types'

/**
 * The SMTP settings as the mail administration and the installation assistant present them
 * (ADR-0033 Entscheidung 10): the same labels, hints and rules in both places. The sentences for
 * missing values mirror `MailSettingsRules` in the backend, which decides in the end.
 */

export const SMTP_DEFAULT_ENCRYPTION: MailEncryption = 'STARTTLS'

export const SMTP_ENCRYPTION_OPTIONS: ReadonlyArray<{
  value: MailEncryption
  label: string
  hint: string
}> = [
  {
    value: 'NONE',
    label: 'Keine',
    hint: 'Unverschlüsseltes SMTP — nur im eigenen, sicheren Netz.',
  },
  {
    value: 'STARTTLS',
    label: 'STARTTLS',
    hint: 'Verschlüsselung wird nach dem Verbindungsaufbau verlangt, nicht still übersprungen.',
  },
  { value: 'SSL', label: 'SSL/TLS', hint: 'Verschlüsselt ab dem ersten Byte (meist Port 465).' },
]

/** Field labels without „(optional)"; each form marks optional fields in its own way. */
export const SMTP_LABELS = {
  host: 'Server',
  port: 'Port',
  encryption: 'Verschlüsselung',
  username: 'Benutzername',
  password: 'Passwort',
  fromAddress: 'Absenderadresse',
  fromName: 'Absendername',
} as const

export const SMTP_TEXTS = {
  hostHint: 'Rechnername oder Adresse des Mailservers, zum Beispiel smtp.intern.example.',
  portHint: 'Üblich: 587 (STARTTLS), 465 (SSL/TLS), 25.',
  portInvalid: 'Bitte eine Portnummer zwischen 1 und 65535 angeben.',
  usernameHint: 'Leer lassen, wenn der Server ohne Anmeldung annimmt.',
  fromAddressHint: 'Die Adresse, unter der OPAA versendet.',
  fromNameHint: 'Erscheint im Postfach vor der Adresse.',
  passwordStored:
    'Ein Passwort ist hinterlegt. Leer lassen lässt es unverändert, solange Server, Port, Benutzername und Verschlüsselung gleich bleiben.',
  passwordNone: 'Es ist kein Passwort hinterlegt.',
  passwordFollowsTarget:
    'Server, Port, Benutzername oder Verschlüsselung ändern sich: Bitte das Passwort neu eingeben oder löschen.',
  enableNeeds: 'Zum Einschalten werden Server, Port und Absenderadresse gebraucht.',
  hostMissing: 'Ohne SMTP-Server kann der Versand nicht eingeschaltet werden',
  portMissing: 'Ohne Port kann der Versand nicht eingeschaltet werden',
  fromAddressMissing: 'Ohne Absenderadresse kann der Versand nicht eingeschaltet werden',
  fromAddressInvalid:
    'Die Absenderadresse muss eine E-Mail-Adresse sein, zum Beispiel opaa@example.org',
} as const

const MIN_PORT = 1
const MAX_PORT = 65535
/** Deliberately permissive, as in the backend: the mail server decides what it accepts. */
const MAIL_ADDRESS = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** The port typed into a field: `null` when empty, `NaN` when it is no port. */
export function parseSmtpPort(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const port = Number(trimmed)
  return Number.isInteger(port) && port >= MIN_PORT && port <= MAX_PORT ? port : Number.NaN
}

/** Why the typed port is no port, or `undefined`; an empty field is no error of this rule. */
export function smtpPortError(value: string): string | undefined {
  return Number.isNaN(parseSmtpPort(value) ?? 0) ? SMTP_TEXTS.portInvalid : undefined
}

export function isMailAddress(value: string): boolean {
  return MAIL_ADDRESS.test(value.trim())
}

/** What a stored SMTP password belongs to. */
export interface SmtpTarget {
  host: string | null
  port: number | null
  username: string | null
  encryption: MailEncryption
}

/**
 * Whether `next` is another target than `stored`: a stored password goes only to the server,
 * port, account and encryption it was entered for.
 */
export function smtpTargetChanged(stored: SmtpTarget, next: SmtpTarget): boolean {
  return (
    (stored.host ?? null) !== (next.host?.trim() || null) ||
    (stored.port ?? null) !== next.port ||
    (stored.username ?? null) !== (next.username?.trim() || null) ||
    stored.encryption !== next.encryption
  )
}
