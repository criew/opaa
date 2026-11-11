import type {
  MailEncryption,
  MalwareScanUnavailablePolicy,
  TextRecognitionConnectionType,
} from '../../api/types'
import { OPAA_BRANDING } from '../../branding/brandingStore'
import { isInvalidPrimaryColor } from '../branding/brandingSettings'
import {
  MALWARE_SCAN_DEFAULT_POLICY,
  MALWARE_SCAN_DEFAULT_PORT,
  MALWARE_SCAN_DEFAULT_TIMEOUT_SECONDS,
  MALWARE_SCAN_MAX_TIMEOUT_SECONDS,
  MALWARE_SCAN_TEXTS,
  malwareScanHostError,
  parseBoundedInt,
} from '../malwareScan/malwareScanSettings'
import {
  SMTP_DEFAULT_ENCRYPTION,
  parseSmtpPort,
  smtpTargetChanged,
  type SmtpTarget,
} from '../smtp/smtpSettings'
import { isBlank, type SetupErrors } from './setupNames'

/**
 * What the field blocks hold while a form is edited, and the rules that turn it into what a save
 * sends. Shared by the installation assistant and the administration, like the blocks themselves.
 */

/** What a save sends for a secret: omitted keeps the stored one, empty removes it. */
export function secretPart(value: string, remove: boolean): string | undefined {
  if (value !== '') return value
  return remove ? '' : undefined
}

/** What the mail server form holds while it is edited; the port as typed. */
export interface SmtpDraft {
  host: string
  port: string
  encryption: MailEncryption
  username: string
  /** Typed in this visit; a stored password is never read back. */
  password: string
  /** The stored password is to be deleted with the next save. */
  removePassword: boolean
  fromAddress: string
  fromName: string
}

/** The stored mail server, as far as the form shows it. */
export interface StoredSmtp extends SmtpTarget {
  fromAddress?: string | null
  fromName?: string | null
  passwordSet: boolean
}

export function smtpDraftOf(stored: StoredSmtp | null): SmtpDraft {
  return {
    host: stored?.host ?? '',
    port: stored?.port == null ? '' : String(stored.port),
    encryption: stored?.encryption ?? SMTP_DEFAULT_ENCRYPTION,
    username: stored?.username ?? '',
    password: '',
    removePassword: false,
    fromAddress: stored?.fromAddress ?? '',
    fromName: stored?.fromName ?? '',
  }
}

/**
 * Whether the stored password no longer fits: it belongs to the server, port, account and
 * encryption it was entered for and must be typed anew or deleted once one of them changes.
 */
export function smtpPasswordNeeded(stored: StoredSmtp | null, draft: SmtpDraft): boolean {
  if (!stored?.passwordSet || draft.removePassword || draft.password !== '') return false
  return smtpTargetChanged(stored, {
    host: draft.host,
    port: parseSmtpPort(draft.port),
    username: draft.username,
    encryption: draft.encryption,
  })
}

/** What the scanner form holds while it is edited; port and time limit as typed. */
export interface MalwareScanDraft {
  host: string
  port: string
  timeout: string
  policy: MalwareScanUnavailablePolicy
}

interface StoredMalwareScan {
  host?: string | null
  port?: number | null
  timeoutSeconds?: number | null
  onUnavailable?: MalwareScanUnavailablePolicy | null
}

/** The stored scanner, or the defaults clamd and OPAA ship with. */
export function malwareScanDraftOf(stored: StoredMalwareScan | null): MalwareScanDraft {
  return {
    host: stored?.host ?? '',
    port: String(stored?.port ?? MALWARE_SCAN_DEFAULT_PORT),
    timeout: String(stored?.timeoutSeconds ?? MALWARE_SCAN_DEFAULT_TIMEOUT_SECONDS),
    policy: stored?.onUnavailable ?? MALWARE_SCAN_DEFAULT_POLICY,
  }
}

export interface MalwareScanValues {
  host: string | null
  port: number
  timeoutSeconds: number
}

/**
 * The draft as numbers, or the field errors that keep it from being sent. A host is required
 * to scan or to test; a form that only stores the values may leave it empty.
 */
export function malwareScanValues(
  draft: MalwareScanDraft,
  hostRequired: boolean,
): { values: MalwareScanValues; errors: null } | { values: null; errors: SetupErrors } {
  const port = parseBoundedInt(draft.port, 1, 65535)
  const timeoutSeconds = parseBoundedInt(draft.timeout, 1, MALWARE_SCAN_MAX_TIMEOUT_SECONDS)
  const host = draft.host.trim()
  const errors = {
    host: hostRequired || host !== '' ? malwareScanHostError(host) : undefined,
    port: Number.isNaN(port) ? MALWARE_SCAN_TEXTS.portInvalid : undefined,
    timeoutSeconds: Number.isNaN(timeoutSeconds) ? MALWARE_SCAN_TEXTS.timeoutInvalid : undefined,
  }
  if (Object.values(errors).some(Boolean)) return { values: null, errors }
  return {
    values: {
      host: host === '' ? null : host,
      port: port ?? MALWARE_SCAN_DEFAULT_PORT,
      timeoutSeconds: timeoutSeconds ?? MALWARE_SCAN_DEFAULT_TIMEOUT_SECONDS,
    },
    errors: null,
  }
}

/** Texts and accent colour as typed; an empty field means the OPAA standard. */
export interface BrandingDraft {
  productName: string
  claim: string
  aiNotice: string
  primaryColor: string
}

/** The colour the previews show: the typed one if it is a colour, else the OPAA standard. */
export function previewColorOf(primaryColor: string): string {
  const color = primaryColor.trim()
  return color === '' || isInvalidPrimaryColor(color) ? OPAA_BRANDING.primaryColor : color
}

export const DEFAULT_MAX_INPUT_TOKENS = 2048
const MAX_TEMPERATURE = 2

/** The temperature as typed, with a decimal comma or point; blank means the model's own. */
export function parseTemperature(value: string): number | null {
  return isBlank(value) ? null : Number(value.trim().replace(',', '.'))
}

export function temperatureError(value: string): string | undefined {
  const number = parseTemperature(value)
  return number === null || (Number.isFinite(number) && number >= 0 && number <= MAX_TEMPERATURE)
    ? undefined
    : `Die Temperatur ist eine Zahl von 0 bis ${MAX_TEMPERATURE}. Für keine Temperatur das Feld leeren.`
}

export function maxTokensError(value: string): string | undefined {
  if (isBlank(value)) return 'Maximale Antwortlänge fehlt'
  const number = Number(value)
  return Number.isInteger(number) && number > 0
    ? undefined
    : 'Die maximale Antwortlänge ist eine ganze Zahl größer als 0.'
}

export function maxInputTokensError(value: string): string | undefined {
  if (isBlank(value)) return undefined
  const number = Number(value)
  return Number.isInteger(number) && number > 0
    ? undefined
    : 'Die Eingabegrenze ist eine ganze Zahl größer als 0.'
}

/** The address of the bundled service in the Compose stack; Kubernetes names it `<release>-ocr`. */
export const BUNDLED_TEXT_RECOGNITION_ADDRESS = 'http://opaa-ocr:5001'

/** An address of the bundled service: `opaa-ocr` (Compose) or `<release>-ocr` (Helm), port 5001. */
export const BUNDLED_ADDRESS_PATTERN = /^http:\/\/[a-z0-9.-]*ocr:5001\/?$/i

/** What the text recognition form holds while it is edited. */
export interface TextRecognitionDraft {
  connectionType: TextRecognitionConnectionType
  baseUrl: string
  /** Only sent for a vision model. */
  model: string
  apiKey: string
  removeKey: boolean
}

interface StoredTextRecognition {
  connectionType: TextRecognitionConnectionType
  baseUrl?: string | null
  model?: string | null
  apiKeySet: boolean
}

/** The stored connection, or the bundled service with its address for a first set-up. */
export function textRecognitionDraftOf(stored: StoredTextRecognition | null): TextRecognitionDraft {
  return {
    connectionType: stored?.connectionType ?? 'DOCLING_SERVE',
    baseUrl: stored?.baseUrl || BUNDLED_TEXT_RECOGNITION_ADDRESS,
    model: stored?.model ?? '',
    apiKey: '',
    removeKey: false,
  }
}

/** Whether the draft names a vision model, whose identifier is then part of the connection. */
export function isVisionDraft(draft: TextRecognitionDraft): boolean {
  return draft.connectionType === 'OPENAI_VISION'
}
