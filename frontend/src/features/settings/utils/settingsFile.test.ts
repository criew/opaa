import { describe, expect, it } from 'vitest'
import { formatSettingValue, parseSettingsFile, settingsFileName } from './settingsFile'

describe('settingsFile', () => {
  it('names the file by the local date of the export', () => {
    expect(settingsFileName(new Date(2026, 9, 5, 23, 30))).toBe(
      'opaa-einstellungen-2026-10-05.json',
    )
  })

  it('accepts only JSON in the export format', () => {
    expect(parseSettingsFile('kein JSON')).toEqual({
      error: 'Die Datei ist kein JSON. Wählen Sie eine Datei aus dem Export von OPAA.',
    })
    expect(parseSettingsFile('[1, 2]')).toEqual({
      error: 'Die Datei ist kein Einstellungsexport von OPAA.',
    })
    expect(
      parseSettingsFile('{"format": "opaa-settings", "formatVersion": 1, "sections": {}}'),
    ).toEqual({ document: { format: 'opaa-settings', formatVersion: 1, sections: {} } })
  })

  it('shows the factory default, switches, empty values and lists readably', () => {
    expect(formatSettingValue(null)).toBe('Werkseinstellung')
    expect(formatSettingValue(true)).toBe('ein')
    expect(formatSettingValue(false)).toBe('aus')
    expect(formatSettingValue('')).toBe('(leer)')
    expect(formatSettingValue([])).toBe('(keine)')
    expect(formatSettingValue(['https://a.example'])).toBe('["https://a.example"]')
    expect(formatSettingValue(36)).toBe('36')
  })
})
