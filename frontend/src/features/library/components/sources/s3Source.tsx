import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import {
  EMPTY_S3_VALUES,
  s3CredentialsOf,
  s3SettingsFromLibrary,
  s3SettingsOf,
  s3ValuesFromSettings,
  s3ValuesIssue,
  type S3SourceValues,
} from '../../utils/s3Source'
import S3Form, { pickedOfScopes } from '../setup/forms/S3Form'
import { collectIssues } from '../setup/collectIssues'
import { S3_PROVIDER_LABELS } from '../../utils/s3Source'
import { S3Scope, S3StoredView } from './S3SourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

export const s3Source: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.S3,
  containerLabel: 'Bucket',
  configuration: {
    empty: EMPTY_S3_VALUES,
    draftFields: [
      'provider',
      'sourceUrl',
      'region',
      'pathStyle',
      'scopes',
      'includePatterns',
      'excludePatterns',
    ],
    // ADR-0027: endpoint, region, addressing style and scopes come back from the stored settings;
    // the stored key stands until a new one is typed.
    fromLibrary: (library): S3SourceValues =>
      s3ValuesFromSettings(library.sourceUrl, s3SettingsFromLibrary(library)),
    isDirty: (values: S3SourceValues) =>
      values.sourceUrl !== '' ||
      values.accessKey !== '' ||
      values.secretKey !== '' ||
      values.sessionToken !== '' ||
      values.scopes.some((scope) => scope.bucket !== '' || scope.prefix !== ''),
    // the stored key survives only on the same origin; a sign-in without a key of
    // the library's own needs none
    validate: (values: S3SourceValues, context) =>
      s3ValuesIssue(
        values,
        !connectionFields(context).asksSecret ||
          (context.credentialsStored &&
            sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)),
      ),
    toPayload: (values: S3SourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: s3CredentialsOf(values),
      sourceSettings: s3SettingsOf(values),
    }),
    nameFromSource: (values: S3SourceValues) => {
      const first = values.scopes.find((scope) => scope.bucket.trim() !== '')
      return first ? first.bucket.trim() : ''
    },
    validateAll: (values: S3SourceValues, context) => {
      const kept =
        !connectionFields(context).asksSecret ||
        (context.credentialsStored &&
          sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl))
      const first = (probe: S3SourceValues) => s3ValuesIssue(probe, kept)
      const validScopes = [{ bucket: 'bucket', prefix: '' }]
      const connectionIssues = collectIssues({ ...values, scopes: validScopes }, first, {
        sourceUrl: 'https://s3.example.org',
        region: 'eu-central-1',
        accessKey: 'key',
        secretKey: 'secret',
        sessionToken: '',
      })
      const repaired = {
        ...values,
        sourceUrl: 'https://s3.example.org',
        region: 'eu-central-1',
        accessKey: 'key',
        secretKey: 'secret',
        sessionToken: '',
      }
      const scopeIssue = s3ValuesIssue(repaired, kept)
      return [
        ...connectionIssues,
        ...(scopeIssue
          ? [{ ...scopeIssue, field: scopeIssue.field?.startsWith('scopes') ? 'scopes' : scopeIssue.field }]
          : []),
      ]
    },
    summary: (values: S3SourceValues, context) => ({
      connection: [
        { label: 'Anbieter', value: S3_PROVIDER_LABELS[values.provider] },
        { label: 'Endpoint', value: values.sourceUrl.trim(), mono: true },
        { label: 'Region', value: values.region.trim(), mono: true },
        {
          label: 'Access Key',
          value: values.accessKey.trim() || (context.credentialsStored ? 'gespeichert' : ''),
          mono: true,
        },
      ],
      selection: {
        rootLabel: 'Buckets',
        noun: { one: 'Bereich', many: 'Bereiche' },
        items: pickedOfScopes(values.scopes),
        empty: 'Kein Bucket gewählt',
      },
    }),
    Form: S3Form,
    StoredView: S3StoredView,
    Scope: S3Scope,
    scopeHero: {
      summary: (library) => {
        const count = s3SettingsFromLibrary(library)?.scopes?.length ?? 0
        return count === 1 ? '1 Geltungsbereich' : `${count} Geltungsbereiche`
      },
      unlistedWarning: (keys) =>
        (keys.length === 1
          ? `Der letzte Vollabgleich konnte den Geltungsbereich „${keys[0]}“ nicht auflisten; sein Bestand ist möglicherweise veraltet.`
          : `Der letzte Vollabgleich konnte die Geltungsbereiche ${keys.map((key) => `„${key}“`).join(', ')} nicht auflisten; ihr Bestand ist möglicherweise veraltet.`) +
        ' Der Hinweis bleibt, bis ein Vollabgleich wieder alle Geltungsbereiche auflisten kann.',
      unlistedTestId: 's3-incomplete-listing-warning',
    },
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Geltungsbereiche; sein Stand bleibt auf der Detailseite sichtbar.',
  },
}
