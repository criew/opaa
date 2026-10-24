import { useCallback } from 'react'
import Typography from '@mui/material/Typography'
import type { LibrarySourceConfigPayload } from '../../utils/librarySourceConfig'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormContext } from '../sources/types'
import ConnectionCheck from './ConnectionCheck'
import FolderTreeScope from './FolderTreeScope'
import SetupField, { StoredCredentials } from './SetupField'
import SourceSetupLayout from './SourceSetupLayout'
import { browseWith, testRequestOf, tokenOf } from './probe'

/** Values of a source on its own server with an account and a folder list (Nextcloud, SMB). */
export interface ServerFolderValues {
  sourceUrl: string
  folders: string
}

interface ServerFolderSetupProps<V extends ServerFolderValues> {
  values: V
  onChange: (patch: Partial<V>) => void
  context: SourceFormContext
  addressLabel: string
  addressPlaceholder: string
  addressHint?: string
  userField: keyof V & string
  secretField: keyof V & string
  secretHint?: string
  /** Credentials in the connector's format, undefined while incomplete. */
  credentialsOf: (values: V) => string | undefined
  /** Whether the stored credentials still apply to the entered address. */
  credentialsKept: boolean
  payloadOf: (values: V) => LibrarySourceConfigPayload
}

/**
 * Nextcloud and Windows-Dateifreigabe on the shared frame: Adresse · Kennung und Kennwort ·
 * folder tree. Under a Zugang without a secret of its own the credentials section is left out.
 */
export default function ServerFolderSetup<V extends ServerFolderValues>({
  values,
  onChange,
  context,
  addressLabel,
  addressPlaceholder,
  addressHint,
  userField,
  secretField,
  secretHint,
  credentialsOf,
  credentialsKept,
  payloadOf,
}: ServerFolderSetupProps<V>) {
  const fields = connectionFields(context)
  const credentials = credentialsOf(values)
  const connectionToken = tokenOf(values.sourceUrl.trim(), credentials ?? null)
  const ready =
    values.sourceUrl.trim() !== '' &&
    (credentials !== undefined || credentialsKept || !fields.asksSecret)
  const browse = useCallback(
    (path: string) =>
      browseWith(context, {
        sourceUrl: values.sourceUrl.trim(),
        sourceCredentials: credentials,
        query: { path },
      }),
    // the listing is remounted with every new connection token
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [connectionToken],
  )
  return (
    <SourceSetupLayout
      intro={
        fields.connection && !fields.asksSecret ? (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Die Anmeldung übernimmt der Zugang „{fields.connection.name}“.
          </Typography>
        ) : undefined
      }
      connection={
        <SetupField
          field="sourceUrl"
          label={addressLabel}
          value={values.sourceUrl}
          onChange={(sourceUrl) => onChange({ sourceUrl } as Partial<V>)}
          placeholder={addressPlaceholder}
          hint={fields.addressHint ?? addressHint}
          mono
        />
      }
      credentials={
        fields.asksSecret ? (
          <StoredCredentials
            stored={credentialsKept}
            label="Kennung und Kennwort"
            onKeep={() => onChange({ [userField]: '', [secretField]: '' } as Partial<V>)}
          >
            <SetupField
              field={userField}
              label="Kennung"
              value={String(values[userField] ?? '')}
              onChange={(next) => onChange({ [userField]: next } as Partial<V>)}
              width="medium"
              autoComplete="off"
            />
            <SetupField
              field={secretField}
              label="Kennwort"
              type="password"
              value={String(values[secretField] ?? '')}
              onChange={(next) => onChange({ [secretField]: next } as Partial<V>)}
              hint={secretHint}
              width="medium"
            />
          </StoredCredentials>
        ) : undefined
      }
      check={
        <ConnectionCheck
          stages={fields.asksSecret ? ['address', 'signIn', 'access'] : ['address', 'access']}
          token={tokenOf(connectionToken, values.folders)}
          request={() => (ready ? testRequestOf(context, payloadOf(values)) : null)}
          missingHint="Bitte zuerst Adresse, Kennung und Kennwort eingeben."
        />
      }
      scope={
        <FolderTreeScope
          folders={values.folders}
          onChange={(folders) => onChange({ folders } as Partial<V>)}
          browse={browse}
          token={connectionToken}
          autoLoad={context.mode === 'edit' && credentialsKept}
        />
      }
    />
  )
}
