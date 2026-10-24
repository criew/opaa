import type { FilesystemSourceValues } from '../../../utils/filesystemSource'
import type { GenericSourceValues } from '../../../utils/librarySourceConfig'
import Stack from '@mui/material/Stack'
import SecretField from '../SecretField'
import SetupTextField from '../SetupTextField'
import type { SetupFieldsProps } from '../setupTypes'
import { credentialsKept } from './common'

export function PathConnectionFields({
  values,
  onChange,
  issueAt,
  fieldId,
}: SetupFieldsProps<FilesystemSourceValues>) {
  return (
    <SetupTextField
      id={fieldId('sourcePath')}
      label="Verzeichnis"
      value={values.sourcePath}
      onChange={(sourcePath) => onChange({ sourcePath })}
      error={issueAt('sourcePath')}
      hint="Absoluter Pfad, von der Systemverwaltung freigegeben."
      placeholder="/data/dokumente"
      mono
    />
  )
}

export function PathScopeFields({
  values,
  onChange,
  issueAt,
  fieldId,
}: SetupFieldsProps<FilesystemSourceValues>) {
  return (
    <SetupTextField
      id={fieldId('excludePatterns')}
      label="Ausschlussmuster"
      optional
      multiline
      value={values.excludePatterns}
      onChange={(excludePatterns) => onChange({ excludePatterns })}
      error={issueAt('excludePatterns')}
      hint="Ein Muster je Zeile, relativ zum Verzeichnis."
      placeholder={'Archiv/**\n**/*.tmp'}
      mono
    />
  )
}

export function UrlConnectionFields({
  values,
  onChange,
  context,
  issueAt,
  fieldId,
}: SetupFieldsProps<GenericSourceValues>) {
  return (
    <Stack spacing={2}>
      <SetupTextField
        id={fieldId('sourceUrl')}
        label="Adresse"
        value={values.sourceUrl}
        onChange={(sourceUrl) => onChange({ sourceUrl })}
        error={issueAt('sourceUrl')}
        placeholder="https://dateien.rheinfurt.example/dokumente/"
        mono
      />
      <SecretField
        id={fieldId('sourceCredentials')}
        label="Zugangsdaten"
        optional
        value={values.sourceCredentials}
        onChange={(sourceCredentials) => onChange({ sourceCredentials })}
        stored={credentialsKept(context, values.sourceUrl)}
        error={issueAt('sourceCredentials')}
        hint="Als benutzername:passwort."
      />
    </Stack>
  )
}
