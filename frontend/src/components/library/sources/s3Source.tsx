import StorageIcon from '@mui/icons-material/Storage'
import { sameLibrarySourceOrigin } from '../../../utils/librarySourceConfig'
import {
  EMPTY_S3_VALUES,
  s3CredentialsOf,
  s3SettingsFromLibrary,
  s3SettingsOf,
  s3ValuesFromSettings,
  validateS3Values,
  type S3SourceValues,
} from '../../../utils/s3Source'
import S3SourceForm from '../S3SourceForm'
import { S3Scope, S3StoredView } from './S3SourceViews'
import { connectionFields } from './sourceConnection'
import type { SourceRegistration } from './types'

export const s3Source: SourceRegistration = {
  label: 'S3-Objektspeicher',
  shortLabel: 'S3',
  description: 'Buckets und Präfixe eines S3-kompatiblen Objektspeichers werden eingelesen.',
  Icon: StorageIcon,
  containerLabel: 'Bucket',
  configuration: {
    empty: EMPTY_S3_VALUES,
    draftFields: [
      'provider',
      'sourceUrl',
      'region',
      'pathStyle',
      'sourceProxy',
      'sourceInsecureSsl',
      'scopes',
      'includePatterns',
      'excludePatterns',
    ],
    // ADR-0027: endpoint, region, addressing style and scopes come back from the stored settings;
    // the stored key stands until a new one is typed.
    fromLibrary: (library): S3SourceValues =>
      s3ValuesFromSettings(
        library.sourceUrl,
        library.sourceProxy,
        library.sourceInsecureSsl,
        s3SettingsFromLibrary(library),
      ),
    isDirty: (values: S3SourceValues) =>
      values.sourceUrl !== '' ||
      values.accessKey !== '' ||
      values.secretKey !== '' ||
      values.sessionToken !== '' ||
      values.scopes.some((scope) => scope.bucket !== '' || scope.prefix !== ''),
    // the stored key survives only on the same origin (#516/#542); a sign-in without a key of
    // the library's own needs none
    validate: (values: S3SourceValues, context) =>
      validateS3Values(
        values,
        !connectionFields(context).asksSecret ||
          (context.credentialsStored &&
            sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)),
      ),
    toPayload: (values: S3SourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceProxy: values.sourceProxy.trim() || undefined,
      sourceCredentials: s3CredentialsOf(values),
      sourceInsecureSsl: values.sourceInsecureSsl,
      sourceSettings: s3SettingsOf(values),
    }),
    nameFromSource: (values: S3SourceValues) => {
      const first = values.scopes.find((scope) => scope.bucket.trim() !== '')
      return first ? first.bucket.trim() : ''
    },
    Form: ({ values, onChange, context }) => (
      <S3SourceForm
        mode={context.mode}
        idPrefix={`${context.idPrefix}-s3`}
        libraryId={context.libraryId}
        credentialsStored={context.credentialsStored}
        originalSourceUrl={context.originalSourceUrl}
        connection={connectionFields(context)}
        values={values}
        onChange={onChange}
      />
    ),
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
