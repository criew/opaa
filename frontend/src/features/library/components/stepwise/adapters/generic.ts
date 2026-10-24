import { linesOf } from './common'
import type { FilesystemSourceValues } from '../../../utils/filesystemSource'
import type { GenericSourceValues } from '../../../utils/librarySourceConfig'
import type { FieldErrors, StepwiseSource } from '../types'
import { addressError } from './common'

export const filesystemStepwise: StepwiseSource<FilesystemSourceValues> = {
  connectionHeading: 'Verbindung zum Dateisystem',
  signsIn: false,
  fields: () => [
    {
      key: 'sourcePath',
      label: 'Verzeichnispfad',
      kind: 'text',
      mono: true,
      placeholder: '/data/dokumente',
      hint: 'Pfad auf dem Server, auf dem OPAA läuft.',
    },
    {
      key: 'excludePatterns',
      label: 'Ausschlussmuster',
      kind: 'multiline',
      mono: true,
      optional: true,
      placeholder: 'Archiv/**',
      hint: 'Ein Muster je Zeile; passende Dateien werden nicht gelesen.',
    },
  ],
  connectionErrors: (values) => {
    const errors: FieldErrors = {}
    const path = values.sourcePath.trim()
    if (path === '') errors.sourcePath = 'Bitte den Verzeichnispfad eingeben.'
    else if (!path.startsWith('/') && !/^[A-Za-z]:[\\/]/.test(path)) {
      errors.sourcePath = 'Der Pfad beginnt mit / (absoluter Pfad).'
    }
    return errors
  },
  stageFields: { address: ['sourcePath'], signIn: [] },
  connectionRows: (values) => [
    { label: 'Verzeichnispfad', value: values.sourcePath, mono: true },
    linesOf(values.excludePatterns).length > 0
      ? { label: 'Ausschlussmuster', value: `${linesOf(values.excludePatterns).length} Muster`, list: linesOf(values.excludePatterns), mono: true }
      : { label: 'Ausschlussmuster', value: 'Keine' },
  ],
}

export const urlStepwise: StepwiseSource<GenericSourceValues> = {
  connectionHeading: 'Verbindung zur Webquelle',
  fields: () => [
    { key: 'sourceUrl', label: 'Adresse', kind: 'text', mono: true, placeholder: 'https://example.de/dokumente/' },
    {
      key: 'sourceCredentials',
      label: 'Zugangsdaten',
      kind: 'secret',
      optional: true,
      credential: true,
      placeholder: 'benutzer:passwort',
    },
  ],
  connectionErrors: (values) => {
    const errors: FieldErrors = {}
    addressError(values.sourceUrl, errors)
    return errors
  },
  stageFields: { address: ['sourceUrl'], signIn: ['sourceCredentials'] },
  signsIn: false,
  connectionRows: (values, { credentialsStored }) => [
    { label: 'Adresse', value: values.sourceUrl, mono: true },
    { label: 'Zugangsdaten', value: values.sourceCredentials ? 'Eingegeben' : credentialsStored ? 'Gesetzt' : 'Keine' },
  ],
}
