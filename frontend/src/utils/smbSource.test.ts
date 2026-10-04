import { describe, expect, it } from 'vitest'
import { sourceRegistration } from '../components/library/sources/registry'
import {
  EMPTY_SMB_VALUES,
  sameSmbServer,
  smbCredentialsOf,
  smbServerOf,
  validateSmbValues,
  type SmbSourceValues,
} from './smbSource'

const values = (patch: Partial<SmbSourceValues>): SmbSourceValues => ({
  ...EMPTY_SMB_VALUES,
  sourceUrl: 'smb://dateiserver/Daten',
  account: 'RATHAUS\\svc-opaa',
  password: 'geheim',
  ...patch,
})

describe('smbSource', () => {
  it('compares stored credentials by server and port, as the backend does', () => {
    expect(smbServerOf('SMB://Dateiserver:445/Daten')).toBe('dateiserver')
    expect(smbServerOf('\\\\Dateiserver\\Daten')).toBe('dateiserver')
    expect(smbServerOf('https://dateiserver/Daten')).toBeNull()
    expect(sameSmbServer('smb://dateiserver/Daten', '\\\\DATEISERVER\\Andere')).toBe(true)
    expect(sameSmbServer('smb://dateiserver/Daten', 'smb://anderer/Daten')).toBe(false)
    expect(sameSmbServer('smb://dateiserver/Daten', 'smb://dateiserver:1445/Daten')).toBe(false)
  })

  it('accepts a share address and refuses anything else', () => {
    expect(validateSmbValues(values({}), false)).toBeNull()
    expect(validateSmbValues(values({ sourceUrl: '\\\\dateiserver\\Daten' }), false)).toBeNull()
    expect(validateSmbValues(values({ sourceUrl: '' }), false)).toMatch(/Adresse der Freigabe/)
    expect(validateSmbValues(values({ sourceUrl: 'https://x/Daten' }), false)).toMatch(/smb:\/\//)
    expect(validateSmbValues(values({ sourceUrl: 'smb://dateiserver' }), false)).toMatch(
      /keine Freigabe/,
    )
    expect(
      validateSmbValues(values({ sourceUrl: 'smb://dateiserver/Daten/Akten' }), false),
    ).toMatch(/Ordner darin/)
  })

  it('wants account and password together, unless stored ones stand', () => {
    expect(validateSmbValues(values({ password: '' }), false)).toMatch(/zusammen/)
    expect(validateSmbValues(values({ account: '', password: '' }), false)).toMatch(/eingeben/)
    expect(validateSmbValues(values({ account: '', password: '' }), true)).toBeNull()
    expect(validateSmbValues(values({ account: 'a:b' }), false)).toMatch(/Doppelpunkt/)
    expect(smbCredentialsOf(values({}))).toBe('RATHAUS\\svc-opaa:geheim')
    expect(smbCredentialsOf(values({ password: '' }))).toBeUndefined()
  })

  it('sends address, credentials and folders, never proxy or certificate switch', () => {
    const configuration = sourceRegistration('SMB')?.configuration
    expect(configuration).toBeTruthy()
    expect(
      configuration!.toPayload(values({ sourceUrl: ' smb://dateiserver/Daten ', folders: '' })),
    ).toEqual({
      sourceUrl: 'smb://dateiserver/Daten',
      sourceCredentials: 'RATHAUS\\svc-opaa:geheim',
      sourceInsecureSsl: false,
      sourceSettings: { folders: ['/'] },
    })
    expect(configuration!.nameFromSource(values({ folders: '/Bauamt/Akten' }))).toBe('Akten')
    expect(
      configuration!.nameFromSource(values({ sourceUrl: 'smb://srv/Gemeinsame%20Daten' })),
    ).toBe('Gemeinsame Daten')
  })
})
