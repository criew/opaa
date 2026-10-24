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
import S3Setup from '../setup/S3Setup'
import { collectIssues } from '../setup/setupContext'
import { accountIssues, addressIssue } from '../setup/validation'
import { listed } from '../setup/summaries'
import { issueAt } from '../../utils/sourceIssue'
import {
  MAX_S3_SCOPES,
  S3_PROVIDER_LABELS,
  s3ScopeKey,
  s3ScopesOverlap,
} from '../../utils/s3Source'
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
      const scopes = values.scopes.filter((scope) => scope.bucket.trim() !== '')
      const overlap = scopes.flatMap((a, i) =>
        scopes
          .slice(i + 1)
          .filter((b) => s3ScopesOverlap(a, b))
          .map((b) => [a, b]),
      )[0]
      return [
        ...collectIssues(addressIssue('sourceUrl', values.sourceUrl)),
        ...(connectionFields(context).asksSecret
          ? accountIssues(
              { field: 'accessKey', value: values.accessKey },
              { field: 'secretKey', value: values.secretKey, label: 'den geheimen Schlüssel' },
              kept,
            ).map((issue) =>
              issue.field === 'accessKey' && issue.message.startsWith('Bitte')
                ? issueAt('accessKey', 'Bitte die Schlüssel-ID eingeben.')
                : issue,
            )
          : []),
        ...collectIssues(
          scopes.length === 0 ? issueAt('scopes', 'Bitte mindestens einen Bucket wählen.') : null,
          scopes.length > MAX_S3_SCOPES
            ? issueAt('scopes', `Höchstens ${MAX_S3_SCOPES} Bereiche je Bibliothek.`)
            : null,
          overlap
            ? issueAt(
                'scopes',
                `„${s3ScopeKey(overlap[0])}“ und „${s3ScopeKey(overlap[1])}“ überschneiden sich.`,
              )
            : null,
        ),
      ]
    },
    checkStages: ['address', 'signIn', 'access'],
    summary: (values: S3SourceValues) => {
      const scopes = values.scopes.filter((scope) => scope.bucket.trim() !== '')
      return {
        connection: [
          { label: 'Anbieter', value: S3_PROVIDER_LABELS[values.provider] },
          { label: 'Endpoint', value: values.sourceUrl.trim(), mono: true },
          ...(values.region.trim()
            ? [{ label: 'Region', value: values.region.trim(), mono: true }]
            : []),
        ],
        credentials: [
          {
            label: 'Schlüssel',
            value: values.accessKey
              ? `${values.accessKey} · geheimer Schlüssel gesetzt`
              : 'gesetzt',
          },
        ],
        scope: [
          {
            label: scopes.length === 1 ? 'Bereich' : `Bereiche (${scopes.length})`,
            value: listed(scopes.map(s3ScopeKey)),
            mono: true,
          },
        ],
      }
    },
    Form: S3Setup,
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
