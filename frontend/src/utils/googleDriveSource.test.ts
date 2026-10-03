import { describe, expect, it } from 'vitest'
import {
  EMPTY_GOOGLE_DRIVE_VALUES,
  googleDrivePayloadOf,
  googleDriveScopeFromKey,
  googleDriveScopesOf,
  validateGoogleDriveValues,
  type GoogleDriveSourceValues,
} from './googleDriveSource'

const values = (patch: Partial<GoogleDriveSourceValues>): GoogleDriveSourceValues => ({
  ...EMPTY_GOOGLE_DRIVE_VALUES,
  keyFile: '{"client_email":"x"}',
  scopes: [{ kind: 'folder', id: 'f1', name: 'Freigabe' }],
  ...patch,
})

describe('googleDriveSource (ADR-0040)', () => {
  it('sends the fixed address, the key only when uploaded, and never switches TLS off', () => {
    expect(googleDrivePayloadOf(values({ sourceProxy: ' proxy:8080 ' }))).toEqual({
      sourceUrl: 'https://www.googleapis.com',
      sourceProxy: 'proxy:8080',
      sourceCredentials: '{"client_email":"x"}',
      sourceInsecureSsl: false,
      sourceSettings: { scopes: [{ folder: 'f1', name: 'Freigabe' }], subject: null },
    })
    expect(googleDrivePayloadOf(values({ keyFile: '' })).sourceCredentials).toBeUndefined()
  })

  it('needs a key, keeps a stored one only for the same imitated account', () => {
    expect(validateGoogleDriveValues(values({ keyFile: '' }), false)).toContain('hochladen')
    expect(
      validateGoogleDriveValues(
        values({ keyFile: '', subject: 'a@example.org', storedSubject: 'a@example.org' }),
        true,
      ),
    ).toBeNull()
    expect(
      validateGoogleDriveValues(
        values({ keyFile: '', subject: 'b@example.org', storedSubject: 'a@example.org' }),
        true,
      ),
    ).toContain('neu hochgeladen')
  })

  it('needs an area and an imitated account for „Meine Ablage“', () => {
    expect(validateGoogleDriveValues(values({ scopes: [] }), false)).toContain('Bereich')
    expect(
      validateGoogleDriveValues(
        values({ scopes: [{ kind: 'myDrive', id: 'root', name: null }] }),
        false,
      ),
    ).toContain('imitiertes Konto')
  })

  it('reads listing keys and stored scopes alike', () => {
    expect(googleDriveScopeFromKey('drive:d1', 'Ablage')).toEqual({
      kind: 'drive',
      id: 'd1',
      name: 'Ablage',
    })
    expect(googleDriveScopeFromKey('folder:a b', null)).toBeNull()
    expect(
      googleDriveScopesOf({
        scopes: [{ drive: 'd1' }, { folder: 'f1', name: 'F' }, { myDrive: true }],
      }),
    ).toEqual([
      { kind: 'drive', id: 'd1', name: null },
      { kind: 'folder', id: 'f1', name: 'F' },
      { kind: 'myDrive', id: 'root', name: 'Meine Ablage' },
    ])
  })
})
