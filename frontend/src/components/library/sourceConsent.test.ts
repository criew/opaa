import { afterEach, describe, expect, it } from 'vitest'
import type { ConnectionProfileOption } from '../../types/api'
import { scheduleValuesFrom } from '../../utils/librarySchedule'
import { registeredSourceTypes, sourceRegistration } from './sources/registry'
import {
  CONSENT_INTENT_STORAGE_KEY,
  attachPendingConnection,
  connectsSource,
  forgetConsentIntent,
  readConsentIntent,
  rememberConsentIntent,
  draftValues,
  type WizardDraft,
} from './sourceConsent'

const option: ConnectionProfileOption = {
  id: 'profile-dropbox',
  name: 'Dropbox Bauamt',
  sourceType: 'NEXTCLOUD',
  serverUrl: 'https://cloud.example',
  authMethod: 'OAUTH',
  ownership: 'LIBRARY',
  ownAccount: false,
  sourceInsecureSsl: false,
  creatable: true,
}

const draft: WizardDraft = {
  sourceType: 'NEXTCLOUD',
  chosenConnections: { NEXTCLOUD: 'profile-dropbox' },
  sourceValues: { sourceUrl: 'https://cloud.example', folders: '/Bauamt' },
  schedule: scheduleValuesFrom(null),
  startFirstRun: true,
  name: 'Bauamt',
  nameTouched: true,
  description: '',
  ownerType: 'USER',
  selectedGroup: null,
  pendingGrants: [],
  responsibleIsGroup: false,
}

describe('sourceConsent', () => {
  afterEach(() => sessionStorage.clear())

  it.each([
    ['OAUTH', 'LIBRARY', false, true],
    ['OAUTH', 'BOTH', false, true],
    ['OAUTH', 'BOTH', true, false],
    ['OAUTH', 'PERSON', false, false],
    ['PERSONAL_SECRET', 'LIBRARY', false, false],
    ['SERVICE_ACCOUNT_KEY', 'LIBRARY', false, false],
  ] as const)(
    'a shared library connects its source only on OAuth for libraries: %s/%s private=%s',
    (authMethod, ownership, privateLibrary, expected) => {
      expect(connectsSource({ ...option, authMethod, ownership }, privateLibrary)).toBe(expected)
    },
  )

  it('connects no source without a profile', () => {
    expect(connectsSource(undefined, false)).toBe(false)
  })

  it('keeps only the fields a form lists for the draft', () => {
    expect(
      draftValues(
        {
          sourceUrl: 'https://cloud.example',
          folders: '/',
          username: 'svc',
          appPassword: 'geheim',
          passphrase: 'geheim',
        },
        ['sourceUrl', 'folders', 'username'],
      ),
    ).toEqual({ sourceUrl: 'https://cloud.example', folders: '/', username: 'svc' })
  })

  it.each(registeredSourceTypes.filter((type) => sourceRegistration(type)?.configuration))(
    'lists for %s only fields of its form, none of them a secret',
    (type) => {
      const configuration = sourceRegistration(type)!.configuration!
      const fields = Object.keys(configuration.empty as object)
      for (const field of configuration.draftFields) {
        expect(fields).toContain(field)
        expect(field).not.toMatch(/password|secret|token|credential|key|pin|passphrase/i)
      }
    },
  )

  it('remembers an intent in this tab only until it is forgotten', () => {
    rememberConsentIntent({ purpose: 'LIBRARY_NEW', profileId: option.id, draft })

    expect(readConsentIntent()).toEqual({ purpose: 'LIBRARY_NEW', profileId: option.id, draft })
    forgetConsentIntent()
    expect(readConsentIntent()).toBeNull()
    expect(sessionStorage.getItem(CONSENT_INTENT_STORAGE_KEY)).toBeNull()
  })

  it('reads a damaged entry as none', () => {
    sessionStorage.setItem(CONSENT_INTENT_STORAGE_KEY, '{kaputt')
    expect(readConsentIntent()).toBeNull()
  })

  it('hands a pending connection only to the wizard that started it on the same profile', () => {
    const pending = {
      id: 'pending-1',
      accountLabel: 'svc@bauamt',
      expiresAt: '2026-10-05T10:00:00Z',
    }
    rememberConsentIntent({ purpose: 'LIBRARY_NEW', profileId: option.id, draft })

    expect(attachPendingConnection('another-profile', pending)).toBe(false)
    expect(attachPendingConnection(option.id, pending)).toBe(true)
    expect(readConsentIntent()).toEqual({
      purpose: 'LIBRARY_NEW',
      profileId: option.id,
      draft,
      pending,
    })
  })

  it('hands a pending connection to nothing without a wizard waiting for it', () => {
    rememberConsentIntent({ purpose: 'LIBRARY_RECONNECT', profileId: option.id, libraryId: 'l-1' })

    expect(
      attachPendingConnection(option.id, { id: 'p', accountLabel: null, expiresAt: '2026-10-05' }),
    ).toBe(false)
  })
})
