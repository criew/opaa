import { describe, expect, it } from 'vitest'
import type { ConnectionAuthMethod } from '../../../types/api'
import {
  connectionFields,
  payloadUnder,
  sourceConnectionOf,
  withConnection,
  withoutFixed,
  type SourceConnection,
} from './sourceConnection'
import type { SourceFormContext } from './types'

const S3_PROFILE: SourceConnection = {
  profileId: 'profile-s3',
  name: 'Speicher Rechenzentrum',
  serverUrl: 'https://s3.rz.example',
  authMethod: 'PERSONAL_SECRET',
  defaults: { region: 'eu-rz-1', pathStyle: true },
}

function context(overrides: Partial<SourceFormContext> = {}): SourceFormContext {
  return {
    mode: 'create',
    sourceType: 'S3',
    idPrefix: 'test',
    credentialsStored: false,
    ...overrides,
  }
}

describe('connectionFields', () => {
  it('derives nothing for a library with its own address', () => {
    const fields = connectionFields(context())

    expect(fields.connection).toBeNull()
    expect(fields.probe).toEqual({})
    expect(fields.asksSecret).toBe(true)
    expect(fields.isFixed('region')).toBe(false)
    expect(fields.addressHint).toBeNull()
    expect(fields.fixedHint).toBeNull()
  })

  it('names the profile in the probes of a new library and the library in those of a stored one', () => {
    expect(connectionFields(context({ connection: S3_PROFILE })).probe).toEqual({
      connectionProfileId: 'profile-s3',
    })
    expect(
      connectionFields(context({ mode: 'edit', libraryId: 'lib-1', connection: S3_PROFILE })).probe,
    ).toEqual({ libraryId: 'lib-1' })
  })

  it('fixes exactly the settings the profile sets, and names it in the hints', () => {
    const fields = connectionFields(context({ connection: S3_PROFILE }))

    expect(fields.isFixed('region')).toBe(true)
    expect(fields.isFixed('pathStyle')).toBe(true)
    expect(fields.isFixed('scopes')).toBe(false)
    expect(fields.fixedHint).toBe('Vom Zugang „Speicher Rechenzentrum“ vorgegeben.')
    expect(fields.addressHint).toContain('https://s3.rz.example')
  })

  it.each<[ConnectionAuthMethod, boolean]>([
    ['NONE', false],
    ['PERSONAL_SECRET', true],
    ['SERVICE_ACCOUNT_KEY', true],
    ['OAUTH', false],
    ['CLIENT_CREDENTIALS', false],
  ])('asks for a secret of its own under %s: %s', (authMethod, asks) => {
    expect(
      connectionFields(context({ connection: { ...S3_PROFILE, authMethod } })).asksSecret,
    ).toBe(asks)
  })
})

describe('withConnection', () => {
  it('prefills an empty address with the profile and sets every fixed field', () => {
    const shown = withConnection(
      { sourceUrl: '', region: 'us-east-1', pathStyle: false, accessKey: '' },
      S3_PROFILE,
    )

    expect(shown).toEqual({
      sourceUrl: 'https://s3.rz.example',
      region: 'eu-rz-1',
      pathStyle: true,
      accessKey: '',
    })
  })

  it('keeps an address already entered and adds no field the form does not have', () => {
    const shown = withConnection({ sourceUrl: 'https://s3.rz.example/x' }, S3_PROFILE)

    expect(shown).toEqual({ sourceUrl: 'https://s3.rz.example/x' })
  })

  it('leaves the values as they are without a profile', () => {
    const values = { sourceUrl: '', region: 'us-east-1' }
    expect(withConnection(values, undefined)).toBe(values)
  })
})

describe('withoutFixed and payloadUnder', () => {
  it('drops the fixed fields from a change, keeping the rest', () => {
    expect(withoutFixed({ region: 'x', sourceUrl: 'https://s3.rz.example/y' }, S3_PROFILE)).toEqual(
      { sourceUrl: 'https://s3.rz.example/y' },
    )
  })

  it('sends no secret for a sign-in without one', () => {
    const payload = {
      sourceUrl: 'https://a.example',
      sourceCredentials: 'u:p',
      sourceInsecureSsl: false,
    }
    const anonymous = connectionFields(
      context({ connection: { ...S3_PROFILE, authMethod: 'NONE' } }),
    )

    expect(payloadUnder(payload, anonymous).sourceCredentials).toBeUndefined()
    expect(payloadUnder(payload, connectionFields(context())).sourceCredentials).toBe('u:p')
  })
})

describe('sourceConnectionOf', () => {
  it('reads an option of the selection, without defaults as none', () => {
    expect(
      sourceConnectionOf({
        id: 'p',
        name: 'Zugang',
        sourceType: 'S3',
        serverUrl: 'https://s3.example',
        authMethod: 'NONE',
        creatable: true,
      }),
    ).toEqual({
      profileId: 'p',
      name: 'Zugang',
      serverUrl: 'https://s3.example',
      authMethod: 'NONE',
      defaults: {},
    })
  })
})
