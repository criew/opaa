import { describe, expect, it } from 'vitest'
import type { ConnectionAuthMethod } from '../../../types/api'
import { registeredSourceTypes, sourceRegistration } from './registry'
import {
  addressAfterSwitch,
  connectionFields,
  payloadUnder,
  rebaseAddress,
  sourceConnectionOf,
  sourceConnectionOfRef,
  switchConnection,
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
    ['SERVICE_ACCOUNT_KEY', false],
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

describe('nothing a profile sets is sent', () => {
  const FRAMED: SourceConnection = {
    ...S3_PROFILE,
    proxy: 'proxy.rz.example:3128',
    insecureSsl: true,
  }

  it('shows proxy and certificate switch of the profile and keeps them out of a change', () => {
    const shown = withConnection(
      { sourceUrl: '', sourceProxy: 'eigener:8080', sourceInsecureSsl: false },
      FRAMED,
    )
    expect(shown).toMatchObject({ sourceProxy: 'proxy.rz.example:3128', sourceInsecureSsl: true })

    const fields = connectionFields(context({ connection: FRAMED }))
    expect(fields.isFixed('sourceProxy')).toBe(true)
    expect(fields.isFixed('sourceInsecureSsl')).toBe(true)
    expect(
      withoutFixed({ sourceProxy: 'x:1', sourceInsecureSsl: false, prefix: 'a' }, FRAMED),
    ).toEqual({ prefix: 'a' })
  })

  it('drops defaults, proxy and the skipped certificate check from a create or update', () => {
    const payload = payloadUnder(
      {
        sourceUrl: 'https://s3.rz.example',
        sourceProxy: 'proxy.rz.example:3128',
        sourceInsecureSsl: true,
        sourceCredentials: 'key:secret',
        sourceSettings: { region: 'eu-rz-1', pathStyle: true, scopes: [{ bucket: 'b' }] },
      },
      connectionFields(context({ connection: FRAMED })),
    )

    expect(payload.sourceProxy).toBeUndefined()
    expect(payload.sourceInsecureSsl).toBe(false)
    expect(payload.sourceSettings).toEqual({ scopes: [{ bucket: 'b' }] })
    expect(payload.sourceCredentials).toBe('key:secret')
  })

  it('frames a test or listing the same way and scopes it', () => {
    const probe = connectionFields(context({ connection: FRAMED })).probeRequest({
      sourceType: 'S3' as const,
      sourceUrl: 'https://s3.rz.example',
      sourceProxy: 'proxy.rz.example:3128',
      sourceInsecureSsl: true,
      query: { region: 'eu-rz-1', pathStyle: true },
    })

    expect(probe).toEqual({
      sourceType: 'S3',
      sourceUrl: 'https://s3.rz.example',
      sourceProxy: undefined,
      sourceInsecureSsl: false,
      query: {},
      connectionProfileId: 'profile-s3',
    })
  })

  it('leaves a request without a profile as it is, apart from the scope', () => {
    const request = {
      sourceType: 'S3' as const,
      sourceUrl: 'https://s3.example',
      sourceProxy: 'eigener:8080',
      sourceInsecureSsl: true,
      sourceSettings: { region: 'eu' },
    }
    expect(connectionFields(context()).probeRequest(request)).toEqual(request)
  })

  it.each(registeredSourceTypes.filter((type) => sourceRegistration(type)?.configuration))(
    'sends no value of the profile from the form of %s',
    (sourceType) => {
      const configuration = sourceRegistration(sourceType)!.configuration!
      const sample = configuration.toPayload(configuration.empty)
      // every setting the form sends counts as set by the profile here - the worst case
      const connection: SourceConnection = { ...FRAMED, defaults: { ...sample.sourceSettings } }
      const values = {
        ...(configuration.empty as object),
        sourceProxy: 'eigener:8080',
        sourceInsecureSsl: true,
      }
      const payload = payloadUnder(
        configuration.toPayload(values),
        connectionFields(context({ sourceType, connection })),
      )

      expect(payload.sourceProxy).toBeUndefined()
      expect(payload.sourceInsecureSsl).toBe(false)
      expect(Object.keys(payload.sourceSettings ?? {})).toEqual([])
    },
  )
})

describe('a switch of profile', () => {
  const NEXT: SourceConnection = {
    ...S3_PROFILE,
    profileId: 'next',
    serverUrl: 'https://neu.example',
  }

  it('keeps an address under the next profile and moves one from under the previous', () => {
    expect(addressAfterSwitch('https://neu.example/a', 'https://alt.example', NEXT)).toBe(
      'https://neu.example/a',
    )
    expect(addressAfterSwitch('https://alt.example/a/b', 'https://alt.example/', NEXT)).toBe(
      'https://neu.example/a/b',
    )
    expect(
      rebaseAddress('https://alt.example', 'https://alt.example', 'https://neu.example/'),
    ).toBe('https://neu.example')
  })

  it('asks anew for an address under neither profile - the repair of a locked library', () => {
    expect(addressAfterSwitch('https://frei.example/x', null, NEXT)).toBeNull()
    expect(addressAfterSwitch('https://frei.example/x', 'https://alt.example', NEXT)).toBeNull()
    expect(addressAfterSwitch(null, 'https://alt.example', NEXT)).toBeNull()
  })

  it('clears what the source read from an address it clears, and the transport of the previous profile', () => {
    type Values = {
      sourceUrl: string
      edition: string | null
      credentialsVerified: boolean
      sourceProxy: string
    }
    const empty: Values = {
      sourceUrl: '',
      edition: null,
      credentialsVerified: false,
      sourceProxy: '',
    }
    const values: Values = {
      sourceUrl: 'https://site.atlassian.net/wiki',
      edition: 'CLOUD',
      credentialsVerified: true,
      sourceProxy: '',
    }

    expect(switchConnection(values, empty, null, NEXT, ['edition', 'credentialsVerified'])).toEqual(
      empty,
    )
    // an address that stays keeps what was read from it
    expect(
      switchConnection({ ...values, sourceUrl: 'https://neu.example/wiki' }, empty, null, NEXT, [
        'edition',
      ]),
    ).toMatchObject({ sourceUrl: 'https://neu.example/wiki', edition: 'CLOUD' })
    expect(
      switchConnection({ ...values, sourceProxy: 'proxy.rz.example:3128' }, empty, NEXT, null),
    ).toMatchObject({ sourceProxy: '' })
  })
})

describe('sourceConnectionOfRef', () => {
  it('reads what a library names of its profile to its managers', () => {
    expect(
      sourceConnectionOfRef({
        id: 'p',
        name: 'Zugang',
        serverUrl: 'https://s3.example',
        authMethod: 'PERSONAL_SECRET',
        connectorDefaults: { region: 'eu' },
        sourceProxy: 'proxy.example:1',
        sourceInsecureSsl: true,
      }),
    ).toEqual({
      profileId: 'p',
      name: 'Zugang',
      serverUrl: 'https://s3.example',
      authMethod: 'PERSONAL_SECRET',
      defaults: { region: 'eu' },
      proxy: 'proxy.example:1',
      insecureSsl: true,
    })
  })

  it('is null while the reference names only id and name', () => {
    expect(sourceConnectionOfRef({ id: 'p', name: 'Zugang' })).toBeNull()
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
        sourceInsecureSsl: false,
        creatable: true,
      }),
    ).toEqual({
      profileId: 'p',
      name: 'Zugang',
      serverUrl: 'https://s3.example',
      authMethod: 'NONE',
      defaults: {},
      proxy: null,
      insecureSsl: false,
    })
  })
})
