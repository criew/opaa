import type { CapabilityOverviewResponse, ConnectionProfileResponse } from '../../../types/api'
import { currentAccess, type Access } from '../capabilities/capabilityAccess'

export const PROFILE_SCOPE_PREFIX = 'PROFILE:'

/** "31.03.2027" for an ISO date, read as a calendar day without a time zone shift. */
export function formatDay(isoDate: string): string {
  const [year, month, day] = isoDate.split('-')
  return `${day}.${month}.${year}`
}

/** Today as an ISO date in the device's zone. */
export function todayIso(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/** The host of a server address for the overview; the address itself where it has none. */
export function hostOf(serverUrl: string | null | undefined): string {
  if (!serverUrl) return '—'
  try {
    return new URL(serverUrl).host || serverUrl
  } catch {
    return serverUrl
  }
}

/** Whether the profile signs in itself, so its sign-in can be tested and rejected. */
export function signsInItself(profile: ConnectionProfileResponse): boolean {
  return profile.authMethod === 'CLIENT_CREDENTIALS' || profile.authMethod === 'SERVICE_ACCOUNT_KEY'
}

/**
 * The release of a profile in the overview of the Anlegerechte. The server lists every profile;
 * one created since the overview was loaded has no grant yet, which is what the stand-in says.
 */
export function profileAccessEntry(
  overview: CapabilityOverviewResponse[],
  profile: ConnectionProfileResponse,
): CapabilityOverviewResponse {
  const scope = `${PROFILE_SCOPE_PREFIX}${profile.id}`
  return (
    overview.find(
      (entry) => entry.capability === 'CREATE_CONNECTOR_LIBRARY' && entry.scope === scope,
    ) ?? {
      capability: 'CREATE_CONNECTOR_LIBRARY',
      scope,
      scopeLabel: `Zugang ${profile.name}`,
      label: 'Konnektorbibliotheken anlegen',
      statement: '',
      grants: [],
    }
  )
}

export type AttentionKind =
  'SIGN_IN_REJECTED' | 'SECRET_EXPIRED' | 'SECRET_EXPIRING' | 'MANY_EXPIRED' | 'NOT_RELEASED'

export interface ProfileAttention {
  kind: AttentionKind
  tone: 'error' | 'warning'
  label: string
  detail: string
}

/**
 * What needs doing about a profile, the most pressing first: a rejected sign-in or an expired
 * secret stop its libraries, an expiring secret soon will, many expired accounts and a profile
 * nobody may use are open tasks. A lock is a deliberate state, not a task. `access` is null while
 * the release is unknown.
 */
export function attentionOf(
  profile: ConnectionProfileResponse,
  access: Access | null,
  today: string = todayIso(),
): ProfileAttention | null {
  if (profile.signInRejected) {
    return {
      kind: 'SIGN_IN_REJECTED',
      tone: 'error',
      label: 'Anmeldung abgelehnt',
      detail:
        'Der Anbieter hat die Anmeldung des Zugangs abgelehnt; seine Bibliotheken laufen nicht.',
    }
  }
  const expiresOn = profile.clientSecretExpiresOn
  if (profile.clientSecretExpiresSoon && expiresOn) {
    return expiresOn < today
      ? {
          kind: 'SECRET_EXPIRED',
          tone: 'error',
          label: `Secret abgelaufen am ${formatDay(expiresOn)}`,
          detail: 'Mit einem abgelaufenen Secret meldet sich der Zugang nicht mehr an.',
        }
      : {
          kind: 'SECRET_EXPIRING',
          tone: 'warning',
          label: `Secret läuft ab am ${formatDay(expiresOn)}`,
          detail: 'Tragen Sie rechtzeitig ein neues Secret und dessen Ablaufdatum ein.',
        }
  }
  if (profile.expiredConnectionWarning) {
    return {
      kind: 'MANY_EXPIRED',
      tone: 'warning',
      label:
        profile.expiredConnectionCount?.count != null
          ? 'Viele Konten abgelaufen'
          : 'Möglicherweise viele Konten abgelaufen',
      detail: 'Die verbundenen Konten erreichen den eingestellten Schwellenwert.',
    }
  }
  if (access?.level === 'ADMIN_ONLY') {
    return {
      kind: 'NOT_RELEASED',
      tone: 'warning',
      label: 'Noch für niemanden freigegeben',
      detail: 'Außer der Systemverwaltung kann niemand diesen Zugang wählen.',
    }
  }
  return null
}

/** The release of a profile, null while the overview of the Anlegerechte is unknown. */
export function accessOf(
  overview: CapabilityOverviewResponse[] | null,
  profile: ConnectionProfileResponse,
): Access | null {
  return overview ? currentAccess(profileAccessEntry(overview, profile)) : null
}

export interface Summary {
  tone: 'success' | 'warning' | 'error'
  headline: string
  detail: string
}

/** The line above the overview: what needs doing across all profiles and resting sources. */
export function summaryOf(attentions: Array<ProfileAttention | null>, dormant: number): Summary {
  const open = attentions.filter((attention): attention is ProfileAttention => attention !== null)
  const parts = [
    open.length > 0 &&
      `${open.length} ${open.length === 1 ? 'Zugang braucht' : 'Zugänge brauchen'} Aufmerksamkeit`,
    dormant > 0 &&
      `${dormant} ${dormant === 1 ? 'Bibliothek mit ruhender Quelle' : 'Bibliotheken mit ruhender Quelle'}`,
  ].filter((part): part is string => Boolean(part))
  if (parts.length === 0) {
    return {
      tone: 'success',
      headline: 'Kein Handlungsbedarf',
      detail: 'Alle Zugänge melden sich an, und keine Quelle einer Bibliothek ruht.',
    }
  }
  return {
    tone: open.some((attention) => attention.tone === 'error') ? 'error' : 'warning',
    headline: parts.join('; '),
    detail: 'Die betroffenen Zeilen sind markiert; öffnen Sie den Zugang, um es zu beheben.',
  }
}
