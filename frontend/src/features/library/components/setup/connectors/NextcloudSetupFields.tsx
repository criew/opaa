import Stack from '@mui/material/Stack'
import { nextcloudCredentialsOf, type NextcloudSourceValues } from '../../../utils/nextcloudSource'
import CredentialFields from '../CredentialFields'
import SetupTextField from '../SetupTextField'
import type { SetupFieldsProps } from '../setupTypes'
import { credentialsKept } from './common'
import FolderScope from './FolderScope'

export function NextcloudConnectionFields({
  values,
  onChange,
  context,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<NextcloudSourceValues>) {
  return (
    <Stack spacing={2}>
      <SetupTextField
        id={fieldId('sourceUrl')}
        label="Adresse"
        value={values.sourceUrl}
        onChange={(sourceUrl) => onChange({ sourceUrl })}
        error={issueAt('sourceUrl')}
        hint={fields.addressHint ?? undefined}
        placeholder="https://cloud.rheinfurt.example"
        mono
      />
      {fields.asksSecret && (
        <CredentialFields
          stored={credentialsKept(context, values.sourceUrl)}
          account={{
            id: fieldId('username'),
            label: 'Benutzername',
            value: values.username,
            onChange: (username) => onChange({ username }),
            error: issueAt('username'),
            placeholder: 'svc-opaa',
          }}
          secret={{
            id: fieldId('appPassword'),
            label: 'App-Passwort',
            value: values.appPassword,
            onChange: (appPassword) => onChange({ appPassword }),
            error: issueAt('appPassword'),
            hint: 'In Nextcloud unter Einstellungen › Sicherheit › App-Passwörter.',
          }}
        />
      )}
    </Stack>
  )
}

export function NextcloudScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<NextcloudSourceValues>) {
  return (
    <FolderScope
      id={fieldId('folders')}
      sourceType="NEXTCLOUD"
      folders={values.folders}
      onChange={(folders) => onChange({ folders })}
      fields={fields}
      connection={{
        sourceUrl: values.sourceUrl.trim(),
        sourceCredentials: nextcloudCredentialsOf(values),
      }}
      error={issueAt('folders')}
    />
  )
}
