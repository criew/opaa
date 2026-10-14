import { describe, expect, it } from 'vitest'
import {
  EMPTY_NEXTCLOUD_VALUES,
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  validateNextcloudValues,
} from './nextcloudSource'

const filled = {
  ...EMPTY_NEXTCLOUD_VALUES,
  sourceUrl: 'https://cloud.example.org',
  username: 'opaa',
  appPassword: 'Ab3-xyz',
  folders: ' /Projekte \n\n/Gruppen/Akten\n/Projekte',
}

describe('the Nextcloud source values', () => {
  it('reads one folder per line without blanks and duplicates', () => {
    expect(nextcloudFoldersOf(filled)).toEqual(['/Projekte', '/Gruppen/Akten'])
  })

  it('joins user and app password, and keeps the stored ones while both are blank', () => {
    expect(nextcloudCredentialsOf(filled)).toBe('opaa:Ab3-xyz')
    expect(nextcloudCredentialsOf({ ...filled, username: '', appPassword: '' })).toBeUndefined()
  })

  it('asks for credentials unless stored ones stand, and for both halves together', () => {
    const blank = { ...filled, username: '', appPassword: '' }
    expect(validateNextcloudValues(blank, false)).toBe(
      'Bitte Benutzername und App-Passwort des technischen Nutzers eingeben',
    )
    expect(validateNextcloudValues(blank, true)).toBeNull()
    expect(validateNextcloudValues({ ...filled, appPassword: '' }, true)).toBe(
      'Bitte Benutzername und App-Passwort zusammen eingeben',
    )
    expect(validateNextcloudValues(filled, false)).toBeNull()
  })

  it('refuses a missing address, an address without scheme and an empty folder list', () => {
    expect(validateNextcloudValues({ ...filled, sourceUrl: '' }, false)).toBe(
      'Bitte die Adresse der Nextcloud eingeben',
    )
    expect(validateNextcloudValues({ ...filled, sourceUrl: 'cloud.example.org' }, false)).toBe(
      'Die Adresse muss mit http:// oder https:// beginnen',
    )
    expect(validateNextcloudValues({ ...filled, folders: ' \n ' }, false)).toBe(
      'Bitte mindestens einen Ordner angeben, / für alles',
    )
  })
})
