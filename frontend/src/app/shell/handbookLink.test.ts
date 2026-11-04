import { describe, expect, it } from 'vitest'
import { HANDBOOK_PAGE_PATHS, handbookUrl, handbookVersionPath } from './handbookLink'

const HANDBOOK_SOURCES = Object.keys(import.meta.glob('../../../../docs/handbuch/**/*.md')).map(
  (path) => path.replace('../../../../docs/handbuch/', ''),
)

describe('handbookLink', () => {
  it('names only pages the handbook has', () => {
    expect(HANDBOOK_SOURCES.length).toBeGreaterThan(50)
    for (const page of HANDBOOK_PAGE_PATHS) {
      expect(HANDBOOK_SOURCES).toContain(page.replace(/\.html$/, '.md'))
    }
  })

  it('opens the page of the view, the start page otherwise', () => {
    expect(handbookUrl('/spaces/abc/settings/members', '0.0.0-dev')).toBe(
      'https://opaa.io/handbuch/benutzer/space-mitglieder.html',
    )
    expect(handbookUrl('/spaces/abc/chats/c1', '0.0.0-dev')).toBe(
      'https://opaa.io/handbuch/benutzer/fragen-stellen.html',
    )
    expect(handbookUrl('/admin/connections/external-tools', '0.0.0-dev')).toBe(
      'https://opaa.io/handbuch/administration/externe-ki-werkzeuge.html',
    )
    expect(handbookUrl('/admin/connections/access', '0.0.0-dev')).toBe(
      'https://opaa.io/handbuch/administration/zugaenge.html',
    )
    expect(handbookUrl('/settings/uploads', '0.0.0-dev')).toBe(
      'https://opaa.io/handbuch/benutzer/anhaengen-und-diktieren.html',
    )
    expect(handbookUrl('/settings/groups', '0.0.0-dev')).toBe(
      'https://opaa.io/handbuch/benutzer/gruppe-pflegen.html',
    )
    expect(handbookUrl('/spaces', '0.0.0-dev')).toBe(
      'https://opaa.io/handbuch/benutzer/erste-schritte.html',
    )
    expect(handbookUrl('/unbekannt', '0.0.0-dev')).toBe('https://opaa.io/handbuch/index.html')
  })

  it('links a release to its own handbook and every other build to main', () => {
    expect(handbookVersionPath('1.2.3')).toBe('v1.2.3/')
    expect(handbookVersionPath('0.1.0-rc.6')).toBe('v0.1.0-rc.6/')
    expect(handbookVersionPath('0.0.0-dev')).toBe('')
    expect(handbookVersionPath('0.0.0-dev+abc1234')).toBe('')
    expect(handbookVersionPath('0.0.0-smoke.1+ci.7')).toBe('')
    expect(handbookVersionPath('0.0.0-smoke.1')).toBe('')
  })
})
