import Stack from '@mui/material/Stack'
import { sameSmbShare, smbCredentialsOf, type SmbSourceValues } from '../../../utils/smbSource'
import CredentialFields from '../CredentialFields'
import SetupTextField from '../SetupTextField'
import type { SetupFieldsProps } from '../setupTypes'
import { credentialsKept } from './common'
import FolderScope from './FolderScope'

export function SmbConnectionFields({
  values,
  onChange,
  context,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<SmbSourceValues>) {
  return (
    <Stack spacing={2}>
      <SetupTextField
        id={fieldId('sourceUrl')}
        label="Adresse"
        value={values.sourceUrl}
        onChange={(sourceUrl) => onChange({ sourceUrl })}
        error={issueAt('sourceUrl')}
        hint={fields.addressHint ?? undefined}
        placeholder="smb://dateiserver/Ablage"
        mono
      />
      {fields.asksSecret && (
        <CredentialFields
          stored={credentialsKept(context, values.sourceUrl, sameSmbShare)}
          account={{
            id: fieldId('account'),
            label: 'Benutzername',
            value: values.account,
            onChange: (account) => onChange({ account }),
            error: issueAt('account'),
            placeholder: 'RHEINFURT\\svc-opaa',
          }}
          secret={{
            id: fieldId('password'),
            label: 'Passwort',
            value: values.password,
            onChange: (password) => onChange({ password }),
            error: issueAt('password'),
          }}
        />
      )}
    </Stack>
  )
}

export function SmbScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<SmbSourceValues>) {
  return (
    <FolderScope
      id={fieldId('folders')}
      sourceType="SMB"
      folders={values.folders}
      onChange={(folders) => onChange({ folders })}
      fields={fields}
      connection={{
        sourceUrl: values.sourceUrl.trim(),
        sourceCredentials: smbCredentialsOf(values),
      }}
      error={issueAt('folders')}
    />
  )
}
