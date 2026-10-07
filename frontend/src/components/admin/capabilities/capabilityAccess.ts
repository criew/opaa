import type { CapabilityOverviewResponse, SelectableGroupResponse } from '../../../types/api'
import { allAccountsLabel } from '../../../utils/labels'

/** Who may use an Anlegerecht, in the three answers the page offers. */
export type AccessLevel = 'ALL' | 'SELECTED' | 'ADMIN_ONLY'

/** A person or group named in a grant, or picked in the panel. */
export interface NamedSubject {
  type: 'USER' | 'GROUP'
  id: string
  name: string
  /** Present for a group picked in this session - needed for the external-provider question. */
  group?: SelectableGroupResponse
}

/**
 * The state of one capability (or one scope of it). `subjects` holds the named persons and groups
 * even while `level` is ALL: they stay granted then, and a later restriction starts from them.
 */
export interface Access {
  level: AccessLevel
  subjects: NamedSubject[]
}

export type AccessStep =
  | {
      kind: 'GRANT'
      subjectType: 'USER' | 'GROUP' | 'ALL_ACCOUNTS'
      subjectId: string | null
      name: string
    }
  | {
      kind: 'REVOKE'
      grantId: string
      subjectType: 'USER' | 'GROUP' | 'ALL_ACCOUNTS'
      name: string
    }

export interface AccessPlan {
  /** Grants first, then revocations, all accounts last - so nobody loses the right in between. */
  steps: AccessStep[]
  withdrawsAllAccounts: boolean
}

function subjectKey(subject: { type: string; id: string }): string {
  return `${subject.type}:${subject.id}`
}

export function namedSubjectsOf(entry: CapabilityOverviewResponse): NamedSubject[] {
  return entry.grants.flatMap((grant) =>
    grant.subjectType === 'ALL_ACCOUNTS' || !grant.subjectId
      ? []
      : [
          {
            type: grant.subjectType,
            id: grant.subjectId,
            name: grant.subjectName ?? 'Nicht mehr auffindbar',
          },
        ],
  )
}

export function currentAccess(entry: CapabilityOverviewResponse): Access {
  const subjects = namedSubjectsOf(entry)
  if (entry.grants.some((grant) => grant.subjectType === 'ALL_ACCOUNTS')) {
    return { level: 'ALL', subjects }
  }
  return { level: subjects.length > 0 ? 'SELECTED' : 'ADMIN_ONLY', subjects }
}

/**
 * The calls that turn the current state of `entry` into `target`. Opening to all accounts keeps
 * the named grants; "only the system administration" withdraws every grant.
 */
export function planAccessChange(entry: CapabilityOverviewResponse, target: Access): AccessPlan {
  const allGrant = entry.grants.find((grant) => grant.subjectType === 'ALL_ACCOUNTS')
  const named = entry.grants.filter((grant) => grant.subjectType !== 'ALL_ACCOUNTS')
  const grants: AccessStep[] = []
  const revokes: AccessStep[] = []

  if (target.level === 'ALL' && !allGrant) {
    grants.push({
      kind: 'GRANT',
      subjectType: 'ALL_ACCOUNTS',
      subjectId: null,
      name: allAccountsLabel,
    })
  }
  if (target.level === 'SELECTED') {
    const held = new Set(named.map((grant) => `${grant.subjectType}:${grant.subjectId}`))
    for (const subject of target.subjects) {
      if (!held.has(subjectKey(subject))) {
        grants.push({
          kind: 'GRANT',
          subjectType: subject.type,
          subjectId: subject.id,
          name: subject.name,
        })
      }
    }
  }
  if (target.level !== 'ALL') {
    const wanted = new Set(
      target.level === 'SELECTED' ? target.subjects.map((subject) => subjectKey(subject)) : [],
    )
    for (const grant of named) {
      if (!wanted.has(`${grant.subjectType}:${grant.subjectId}`)) {
        revokes.push({
          kind: 'REVOKE',
          grantId: grant.id,
          subjectType: grant.subjectType,
          name: grant.subjectName ?? 'Nicht mehr auffindbar',
        })
      }
    }
    if (allGrant) {
      revokes.push({
        kind: 'REVOKE',
        grantId: allGrant.id,
        subjectType: 'ALL_ACCOUNTS',
        name: allAccountsLabel,
      })
    }
  }
  return {
    steps: [...grants, ...revokes],
    withdrawsAllAccounts: Boolean(allGrant) && target.level !== 'ALL',
  }
}

function joinNames(names: string[]): string {
  if (names.length <= 1) return names.join('')
  return `${names.slice(0, -1).join(', ')} und ${names[names.length - 1]}`
}

function distinctSubjects(subjects: NamedSubject[]): NamedSubject[] {
  const seen = new Set<string>()
  return subjects.filter((subject) => {
    const key = subjectKey(subject)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** "Referat 32 Ordnung, Ines Vogel und die Systemverwaltung dürfen Spaces anlegen." */
export function resultSentence(access: Access, phrase: string): string {
  if (access.level === 'ALL') return `${allAccountsLabel} dürfen ${phrase}.`
  if (access.level === 'ADMIN_ONLY') return `Nur die Systemverwaltung darf ${phrase}.`
  const names = distinctSubjects(access.subjects).map((subject) => subject.name)
  return `${joinNames([...names, 'die Systemverwaltung'])} dürfen ${phrase}.`
}

function counted(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural}`
}

/** The short form for the status badge: "Alle Konten", "2 Gruppen, 1 Person", … */
export function accessBadgeLabel(access: Access): string {
  if (access.level === 'ALL') return allAccountsLabel
  if (access.level === 'ADMIN_ONLY') return 'Nur Systemverwaltung'
  const subjects = distinctSubjects(access.subjects)
  const groups = subjects.filter((subject) => subject.type === 'GROUP').length
  const persons = subjects.length - groups
  return [
    groups > 0 ? counted(groups, 'Gruppe', 'Gruppen') : null,
    persons > 0 ? counted(persons, 'Person', 'Personen') : null,
  ]
    .filter(Boolean)
    .join(', ')
}

export type ScopeKind = 'TYPE' | 'PROFILE'

export function scopeKind(entry: CapabilityOverviewResponse): ScopeKind | null {
  if (!entry.scope) return null
  return entry.scope.startsWith('PROFILE:') ? 'PROFILE' : 'TYPE'
}

/** "Quellart Confluence" → "Confluence", "Zugang Nextcloud intern" → "Nextcloud intern". */
export function scopeShortLabel(entry: CapabilityOverviewResponse): string {
  const label = entry.scopeLabel ?? entry.scope ?? ''
  return label.replace(/^(Quellart|Zugang)\s+/, '')
}

const OBJECT_PHRASES: Partial<Record<CapabilityOverviewResponse['capability'], string>> = {
  CREATE_SPACE: 'Spaces anlegen',
  CREATE_LIBRARY: 'Bibliotheken für Uploads anlegen',
  CREATE_CONNECTOR_LIBRARY: 'Bibliotheken mit Inhalten aus anderen Systemen anlegen',
  CREATE_INTERNAL_GROUP: 'interne Gruppen anlegen',
  CREATE_PROMPT_LIBRARY: 'Prompt-Bibliotheken anlegen',
}

/** What the right allows, as the end of a sentence: "… dürfen Spaces anlegen". */
export function objectPhrase(entry: CapabilityOverviewResponse): string {
  const kind = scopeKind(entry)
  if (kind === 'TYPE') return `Bibliotheken mit Inhalten aus ${scopeShortLabel(entry)} anlegen`
  if (kind === 'PROFILE') {
    return `Bibliotheken über den Zugang „${scopeShortLabel(entry)}“ anlegen`
  }
  return OBJECT_PHRASES[entry.capability] ?? entry.label
}

export interface AccessBucket {
  level: AccessLevel
  label: string
  entries: CapabilityOverviewResponse[]
}

/** Scopes with the same state side by side, so the deviating ones stand out. */
export function bucketByAccess(entries: CapabilityOverviewResponse[]): AccessBucket[] {
  const buckets = new Map<string, AccessBucket>()
  for (const entry of entries) {
    const access = currentAccess(entry)
    const label =
      access.level === 'SELECTED'
        ? distinctSubjects(access.subjects)
            .map((subject) => subject.name)
            .join(', ')
        : accessBadgeLabel(access)
    const bucket = buckets.get(`${access.level}:${label}`)
    if (bucket) bucket.entries.push(entry)
    else buckets.set(`${access.level}:${label}`, { level: access.level, label, entries: [entry] })
  }
  return [...buckets.values()]
}
