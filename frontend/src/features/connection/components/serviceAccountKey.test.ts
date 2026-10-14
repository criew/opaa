import { describe, expect, it } from 'vitest'
import { MAX_GOOGLE_DRIVE_KEY_LENGTH, readGoogleDriveKey } from './serviceAccountKey'

const key = {
  type: 'service_account',
  client_email: 'opaa-reader@bauamt.iam.gserviceaccount.com',
  private_key_id: 'abc123',
  private_key: '-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----\n',
}

describe('readGoogleDriveKey', () => {
  it('reads the account of a service-account key file', () => {
    expect(readGoogleDriveKey(JSON.stringify(key))).toEqual({
      account: 'opaa-reader@bauamt.iam.gserviceaccount.com',
    })
  })

  it.each(['client_email', 'private_key_id', 'private_key'])(
    'rejects a file without %s',
    (field) => {
      const incomplete: Record<string, unknown> = { ...key }
      delete incomplete[field]

      expect(readGoogleDriveKey(JSON.stringify(incomplete))).toEqual({
        error:
          'Die Datei ist keine JSON-Schlüsseldatei eines Dienstkontos (erwartet client_email, private_key_id und private_key).',
      })
    },
  )

  it('rejects a file that is no JSON', () => {
    expect(readGoogleDriveKey('client_email=svc')).toHaveProperty('error')
  })

  it('rejects a file longer than the API accepts before reading it', () => {
    const tooLong = JSON.stringify({ ...key, padding: 'x'.repeat(MAX_GOOGLE_DRIVE_KEY_LENGTH) })

    expect(readGoogleDriveKey(tooLong)).toEqual({
      error: `Die Schlüsseldatei ist größer als ${MAX_GOOGLE_DRIVE_KEY_LENGTH} Zeichen.`,
    })
  })
})
