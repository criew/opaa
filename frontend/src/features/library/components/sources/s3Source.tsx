import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import {
  EMPTY_S3_VALUES,
  s3CredentialsOf,
  s3SettingsFromLibrary,
  s3SettingsOf,
  s3ValuesFromSettings,
  s3ScopeKey,
  s3ValuesIssue,
  S3_PROVIDER_LABELS,
  type S3SourceValues,
} from '../../utils/s3Source'
import S3Form from '../sourceForms/S3Form'
import { collectIssues, unifyMissing } from '../sourceKit/collectIssues'
import { listSummary } from '../sourceKit/summaries'
import { S3StoredView } from './S3SourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import { s3Item } from '../sourceKit/selectionSources'
import StoredSelection from '../sourceKit/StoredSelection'
import type { SourceFormContext, SourceRegistration } from './types'

const s3Issues = (values: S3SourceValues, context: SourceFormContext) =>
  unifyMissing(
    collectIssues(
      values,
      (v) =>
        s3ValuesIssue(
          v,
          !connectionFields(context).asksSecret ||
            (context.credentialsStored &&
              sameLibrarySourceOrigin(context.originalSourceUrl, v.sourceUrl)),
        ),
      {
        sourceUrl: 'https://stand.in',
        region: '',
        accessKey: 'stand-in',
        secretKey: 'stand-in',
        sessionToken: '',
      },
      (field) => (field.startsWith('scopes.') ? 'selection' : field),
    ),
    values,
    { sourceUrl: 'die Adresse', accessKey: 'die Kennung', secretKey: 'das Kennwort' },
  )

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
    issues: s3Issues,
    validate: (values: S3SourceValues, context) => s3Issues(values, context)[0] ?? null,
    summary: (values: S3SourceValues) => [
      { label: 'Anbieter', value: S3_PROVIDER_LABELS[values.provider] },
      { label: 'Adresse', value: values.sourceUrl.trim() },
      ...(values.region.trim() ? [{ label: 'Region', value: values.region.trim() }] : []),
      listSummary(
        'Buckets und Präfixe',
        values.scopes.filter((scope) => scope.bucket.trim() !== '').map(s3ScopeKey),
        'Keine',
      ),
    ],
    toPayload: (values: S3SourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: s3CredentialsOf(values),
      sourceSettings: s3SettingsOf(values),
    }),
    nameFromSource: (values: S3SourceValues) => {
      const first = values.scopes.find((scope) => scope.bucket.trim() !== '')
      return first ? first.bucket.trim() : ''
    },
    Form: S3Form,
    StoredView: S3StoredView,
    Scope: ({ library }) => (
      <StoredSelection
        label="Buckets und Präfixe"
        items={(s3SettingsFromLibrary(library)?.scopes ?? []).map((scope) =>
          s3Item({ bucket: scope.bucket, prefix: scope.prefix ?? '' }),
        )}
        emptyText="Keine"
      />
    ),
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
