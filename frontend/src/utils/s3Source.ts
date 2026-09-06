import type { S3ScopeRef, S3Settings } from '../types/api'

/**
 * The provider templates of the wizard (ADR-0027, Entscheidung 10): each one prefills endpoint
 * shape, region and addressing style; every prefill stays editable, because a template is a guess.
 */
export type S3Provider = 'AWS' | 'MINIO' | 'CEPH' | 'HETZNER' | 'OTHER'

export interface S3ScopeInput {
  bucket: string
  prefix: string
}

/**
 * Everything the S3 source configuration consists of (ADR-0027): the endpoint, region and
 * addressing style, the static key, the verification state, one to fifty scopes and the key
 * patterns. Owned by the wizard or the edit dialog; the form only proposes changes through
 * onChange. Patterns are kept as one text with one pattern per line.
 */
export interface S3SourceValues {
  provider: S3Provider
  sourceUrl: string
  region: string
  pathStyle: boolean
  sourceProxy: string
  sourceInsecureSsl: boolean
  accessKey: string
  secretKey: string
  sessionToken: string
  /** True once the store accepted the key (or, in edit mode, while the stored one stands). */
  credentialsVerified: boolean
  scopes: S3ScopeInput[]
  includePatterns: string
  excludePatterns: string
}

/** Mirrors S3Scope.MAX_PER_LIBRARY - the backend rejects a larger selection with 400. */
export const MAX_S3_SCOPES = 50
/** Mirrors S3SourceSettings.MAX_PATTERNS / MAX_PATTERN_LENGTH. */
export const MAX_S3_PATTERNS = 50
export const MAX_S3_PATTERN_LENGTH = 255
const MAX_S3_PREFIX_LENGTH = 1023

export const S3_PROVIDER_LABELS: Record<S3Provider, string> = {
  AWS: 'AWS S3',
  MINIO: 'MinIO / S3-kompatibel',
  CEPH: 'Ceph RGW',
  HETZNER: 'Hetzner Object Storage',
  OTHER: 'Anderer Anbieter',
}

export const S3_PROVIDERS: S3Provider[] = ['AWS', 'MINIO', 'CEPH', 'HETZNER', 'OTHER']

/** The AWS regions the region field suggests; any other region can still be typed. */
export const AWS_REGIONS = [
  'eu-central-1',
  'eu-central-2',
  'eu-west-1',
  'eu-west-2',
  'eu-west-3',
  'eu-north-1',
  'eu-south-1',
  'eu-south-2',
  'us-east-1',
  'us-east-2',
  'us-west-1',
  'us-west-2',
]

/** Hetzner's locations double as the signing region. */
export const HETZNER_LOCATIONS = ['fsn1', 'nbg1', 'hel1']

export const EMPTY_S3_VALUES: S3SourceValues = {
  provider: 'MINIO',
  sourceUrl: '',
  region: 'us-east-1',
  pathStyle: true,
  sourceProxy: '',
  sourceInsecureSsl: false,
  accessKey: '',
  secretKey: '',
  sessionToken: '',
  credentialsVerified: false,
  scopes: [{ bucket: '', prefix: '' }],
  includePatterns: '',
  excludePatterns: '',
}

/** The endpoint a template derives from its region, or null when the provider has no rule. */
export function derivedS3Endpoint(provider: S3Provider, region: string): string | null {
  const r = region.trim()
  if (provider === 'AWS') {
    return r ? `https://s3.${r}.amazonaws.com` : ''
  }
  if (provider === 'HETZNER') {
    return r ? `https://${r}.your-objectstorage.com` : ''
  }
  return null
}

/** What choosing a provider prefills (ADR-0027, Entscheidung 10) - the credentials and scopes stay. */
export function s3ProviderTemplate(provider: S3Provider): Partial<S3SourceValues> {
  switch (provider) {
    case 'AWS':
      return {
        provider,
        region: 'eu-central-1',
        pathStyle: false,
        sourceUrl: derivedS3Endpoint('AWS', 'eu-central-1') ?? '',
      }
    case 'HETZNER':
      return {
        provider,
        region: 'fsn1',
        pathStyle: false,
        sourceUrl: derivedS3Endpoint('HETZNER', 'fsn1') ?? '',
      }
    case 'MINIO':
    case 'CEPH':
      return { provider, region: 'us-east-1', pathStyle: true, sourceUrl: '' }
    case 'OTHER':
      return { provider, region: '', pathStyle: true, sourceUrl: '' }
  }
}

/**
 * Joins the entered key into the stored form accessKey:secretKey[:sessionToken] (ADR-0027,
 * Entscheidung 7); undefined when neither key was entered, so stored credentials stay.
 */
export function s3CredentialsOf(values: S3SourceValues): string | undefined {
  const accessKey = values.accessKey.trim()
  const secretKey = values.secretKey.trim()
  const sessionToken = values.sessionToken.trim()
  if (!accessKey && !secretKey) return undefined
  return `${accessKey}:${secretKey}${sessionToken ? `:${sessionToken}` : ''}`
}

/** One pattern per line, blank lines dropped, whitespace trimmed. */
export function s3PatternsOf(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

/** Mirrors S3Scope.normalizePrefix: no leading slash, a trailing slash when not empty. */
export function normalizeS3Prefix(raw: string): string {
  let prefix = raw.trim()
  while (prefix.startsWith('/')) prefix = prefix.slice(1)
  if (prefix === '') return ''
  return prefix.endsWith('/') ? prefix : `${prefix}/`
}

/** Mirrors S3Scope.validateBucket: the AWS naming rules MinIO and Ceph adopt. */
export function isValidS3BucketName(bucket: string): boolean {
  const name = bucket.trim()
  return (
    /^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(name) &&
    !name.includes('..') &&
    !/^\d{1,3}(\.\d{1,3}){3}$/.test(name)
  )
}

/** bucket/prefix, or the bare bucket for the whole-bucket scope. */
export function s3ScopeKey(scope: S3ScopeInput): string {
  const prefix = normalizeS3Prefix(scope.prefix)
  return prefix ? `${scope.bucket.trim()}/${prefix}` : scope.bucket.trim()
}

/** Whether the two scopes share at least one possible key (same bucket, one prefix under the other). */
export function s3ScopesOverlap(a: S3ScopeInput, b: S3ScopeInput): boolean {
  if (a.bucket.trim() !== b.bucket.trim()) return false
  const pa = normalizeS3Prefix(a.prefix)
  const pb = normalizeS3Prefix(b.prefix)
  return pa.startsWith(pb) || pb.startsWith(pa)
}

/** The typed settings as LibraryRequest.s3Settings / SourceConnectionTestRequest.s3Settings carry them. */
export function s3SettingsOf(values: S3SourceValues): S3Settings {
  const scopes: S3ScopeRef[] = values.scopes
    .filter((scope) => scope.bucket.trim() !== '')
    .map((scope) => ({
      bucket: scope.bucket.trim(),
      prefix: normalizeS3Prefix(scope.prefix) || null,
    }))
  const region = values.region.trim()
  return {
    region: region || null,
    pathStyle: values.pathStyle,
    scopes,
    includePatterns: s3PatternsOf(values.includePatterns),
    excludePatterns: s3PatternsOf(values.excludePatterns),
  }
}

function hostnameOf(url: string): string {
  try {
    return new URL(url).hostname.toLowerCase()
  } catch {
    return ''
  }
}

/** The form values an existing library's stored configuration reads back into. */
export function s3ValuesFromSettings(
  sourceUrl: string | null | undefined,
  sourceProxy: string | null | undefined,
  sourceInsecureSsl: boolean | null | undefined,
  settings: S3Settings | null | undefined,
  credentialsStored: boolean,
): S3SourceValues {
  const url = sourceUrl ?? ''
  const host = hostnameOf(url)
  const provider: S3Provider = host.endsWith('.amazonaws.com')
    ? 'AWS'
    : host.endsWith('.your-objectstorage.com')
      ? 'HETZNER'
      : settings?.pathStyle === false
        ? 'OTHER'
        : 'MINIO'
  return {
    ...EMPTY_S3_VALUES,
    provider,
    sourceUrl: url,
    region: settings?.region ?? '',
    pathStyle: settings?.pathStyle ?? true,
    sourceProxy: sourceProxy ?? '',
    sourceInsecureSsl: Boolean(sourceInsecureSsl),
    credentialsVerified: credentialsStored,
    scopes:
      settings?.scopes && settings.scopes.length > 0
        ? settings.scopes.map((scope) => ({ bucket: scope.bucket, prefix: scope.prefix ?? '' }))
        : [{ bucket: '', prefix: '' }],
    includePatterns: (settings?.includePatterns ?? []).join('\n'),
    excludePatterns: (settings?.excludePatterns ?? []).join('\n'),
  }
}

/** The German message for one scope row, or null when the row is fine. */
export function validateS3Scope(scope: S3ScopeInput): string | null {
  const bucket = scope.bucket.trim()
  if (!bucket) return 'Der Bucket-Name ist erforderlich.'
  if (!isValidS3BucketName(bucket)) {
    return `Der Bucket-Name „${bucket}“ ist ungültig: 3 bis 63 Zeichen, nur Kleinbuchstaben, Ziffern, Punkte und Bindestriche, beginnend und endend mit Buchstabe oder Ziffer, keine IP-Adresse.`
  }
  const prefix = normalizeS3Prefix(scope.prefix)
  if (prefix.length > MAX_S3_PREFIX_LENGTH + 1) {
    return `Das Präfix darf höchstens ${MAX_S3_PREFIX_LENGTH} Zeichen lang sein.`
  }
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u0020\u007f]/.test(prefix)) {
    return `Das Präfix „${prefix}“ enthält Leer- oder Steuerzeichen und ist kein Schlüssel.`
  }
  return null
}

/**
 * The S3 stages in order (ADR-0027): endpoint, key, at least one valid, non-overlapping scope,
 * usable patterns - the first missing one is the message, so the wizard points at the next step.
 * `credentialsMayBeStored` is edit mode with a stored key: the key fields may stay empty then.
 */
export function validateS3Values(
  values: S3SourceValues | undefined,
  credentialsMayBeStored = false,
): string | null {
  if (!values) return 'Endpoint des Objektspeichers ist erforderlich'
  const url = values.sourceUrl.trim()
  if (!url) return 'Endpoint des Objektspeichers ist erforderlich'
  if (!/^https?:\/\//i.test(url)) {
    return 'Endpoint des Objektspeichers muss mit http:// oder https:// beginnen'
  }
  const accessKey = values.accessKey.trim()
  const secretKey = values.secretKey.trim()
  if (!credentialsMayBeStored || accessKey || secretKey) {
    if (!accessKey) return 'Access Key ist erforderlich'
    if (!secretKey) return 'Secret Key ist erforderlich'
    if (accessKey.includes(':') || secretKey.includes(':')) {
      return 'Access Key und Secret Key dürfen keinen Doppelpunkt enthalten'
    }
    if (values.sessionToken.includes(':')) {
      return 'Das Session-Token darf keinen Doppelpunkt enthalten'
    }
  }
  const scopes = values.scopes.filter(
    (scope) => scope.bucket.trim() !== '' || scope.prefix.trim() !== '',
  )
  if (scopes.length === 0) return 'Bitte mindestens einen Geltungsbereich (Bucket) angeben'
  if (scopes.length > MAX_S3_SCOPES) {
    return `Höchstens ${MAX_S3_SCOPES} Geltungsbereiche je Bibliothek`
  }
  for (const scope of scopes) {
    const message = validateS3Scope(scope)
    if (message) return message
  }
  for (let i = 0; i < scopes.length; i += 1) {
    for (let j = 0; j < i; j += 1) {
      if (s3ScopesOverlap(scopes[i], scopes[j])) {
        return `Die Geltungsbereiche „${s3ScopeKey(scopes[j])}“ und „${s3ScopeKey(scopes[i])}“ überschneiden sich; jedes Objekt darf nur in einem Bereich liegen.`
      }
    }
  }
  for (const [label, text] of [
    ['Einschlussmuster', values.includePatterns],
    ['Ausschlussmuster', values.excludePatterns],
  ] as const) {
    const patterns = s3PatternsOf(text)
    if (patterns.length > MAX_S3_PATTERNS) {
      return `Höchstens ${MAX_S3_PATTERNS} ${label} je Bibliothek`
    }
    const tooLong = patterns.find((pattern) => pattern.length > MAX_S3_PATTERN_LENGTH)
    if (tooLong) {
      return `${label}: ein Muster darf höchstens ${MAX_S3_PATTERN_LENGTH} Zeichen lang sein`
    }
    if (new Set(patterns).size !== patterns.length) {
      return `${label}: ein Muster ist mehrfach angegeben`
    }
  }
  return null
}
