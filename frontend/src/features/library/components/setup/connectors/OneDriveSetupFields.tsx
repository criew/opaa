import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { browseSource } from '../../../api/libraryApi'
import {
  ONEDRIVE_API,
  oneDriveLabel,
  type OneDriveSourceValues,
} from '../../../utils/oneDriveSource'
import type { ConnectionFields } from '../../sources/sourceConnection'
import { plainMessage } from '../../sourceKit/plainMessage'
import {
  oneDriveFoldersOfItems,
  oneDriveItemsOf,
  oneDriveSelectionSource,
} from '../../sourceKit/selectionSources'
import ScopePicker from '../ScopePicker'
import { displayPath } from '../paths'
import { TEXT_LINK } from '../rowStyles'
import type { SetupFieldsProps } from '../setupTypes'

type Found = { driveId: string; driveName: string | null } | { refusal: string }

/** The one OneDrive the connected account owns, or why it cannot be read (ADR-0058). */
async function ownDrive(fields: ConnectionFields): Promise<Found> {
  try {
    const response = await browseSource(
      'ONEDRIVE',
      fields.probeRequest({ sourceUrl: ONEDRIVE_API }),
    )
    const entry = response.entries.find((candidate) => candidate.key.startsWith('drive:'))
    if (entry) {
      return { driveId: entry.key.slice('drive:'.length), driveName: entry.name?.trim() || null }
    }
    return {
      refusal:
        response.message ?? 'Für das verbundene Microsoft-Konto ist kein OneDrive bereitgestellt.',
    }
  } catch (err) {
    return { refusal: err instanceof Error && err.message ? err.message : '' }
  }
}

/**
 * Finds the OneDrive of the connected account whenever none is known for the way of connection
 * the values were made under, and again on `choose()`. A different OneDrive than before drops the
 * folders, which belong to the old one; a refused lookup after a change of the way drops the old
 * OneDrive as well.
 */
function useOwnDrive({ values, onChange, fields }: SetupFieldsProps<OneDriveSourceValues>) {
  const probeKey = JSON.stringify(fields.probe)
  const [asked, setAsked] = useState(0)
  const [outcome, setOutcome] = useState<{ token: string; refusal: string | null } | null>(null)
  const latest = useRef({ values, onChange, fields })
  useEffect(() => {
    latest.current = { values, onChange, fields }
  })
  const unknown =
    values.driveId === '' || (values.driveFoundWith !== null && values.driveFoundWith !== probeKey)
  const wanted = unknown || asked > 0
  const token = `${probeKey}#${asked}`

  useEffect(() => {
    if (!wanted) return
    let alive = true
    void ownDrive(latest.current.fields).then((found) => {
      if (!alive) return
      if ('refusal' in found) {
        setOutcome({ token, refusal: found.refusal })
        // a OneDrive found under another way of connection is not this account's: drop it
        const before = latest.current.values
        if (before.driveFoundWith !== null && before.driveFoundWith !== probeKey) {
          latest.current.onChange({
            driveId: '',
            driveName: null,
            driveFoundWith: null,
            folders: [],
            allFolders: true,
          })
        }
        return
      }
      setOutcome({ token, refusal: null })
      const before = latest.current.values
      latest.current.onChange({
        driveId: found.driveId,
        driveName: found.driveName,
        driveFoundWith: probeKey,
        ...(before.driveId !== '' && before.driveId !== found.driveId
          ? { folders: [], allFolders: true }
          : {}),
      })
    })
    return () => {
      alive = false
    }
    // the token names everything the lookup depends on
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wanted, token])

  const current = outcome?.token === token ? outcome : null
  return {
    loading: wanted && current === null,
    refusal: current?.refusal ?? null,
    choose: () => setAsked((n) => n + 1),
  }
}

function TextLink({ onClick, children }: { onClick: () => void; children: string }) {
  return (
    <Link component="button" type="button" underline="hover" onClick={onClick} sx={TEXT_LINK}>
      {children}
    </Link>
  )
}

/** Where the lookup of the OneDrive stands: found, under way, or refused with the reason. */
function DriveState({
  props,
  drive,
}: {
  props: SetupFieldsProps<OneDriveSourceValues>
  drive: ReturnType<typeof useOwnDrive>
}) {
  const { values, issueAt, fieldId, editing } = props
  const id = fieldId('drive')
  const issue = issueAt('drive')
  let body: React.ReactNode
  if (drive.loading) {
    body = (
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
        OneDrive wird ermittelt …
      </Typography>
    )
  } else if (drive.refusal !== null) {
    const detail = plainMessage(drive.refusal)
    body = (
      <>
        <Typography sx={{ fontSize: 13.5, color: 'text.primary' }}>
          Das OneDrive ließ sich nicht ermitteln.
        </Typography>
        {detail && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{detail}</Typography>}
      </>
    )
  } else if (values.driveId !== '') {
    body = (
      <Typography sx={{ fontSize: 13.5, overflowWrap: 'anywhere' }}>
        „{oneDriveLabel(values)}“ – das OneDrive Ihres verbundenen Microsoft-Kontos.
      </Typography>
    )
  }
  return (
    <Box id={id} aria-describedby={issue ? `${id}-error` : undefined}>
      <FieldLabel id={`${id}-label`}>OneDrive</FieldLabel>
      <Box role="status">{body}</Box>
      {!drive.loading && (drive.refusal !== null || (editing && values.driveId !== '')) && (
        <Box sx={{ mt: 0.5 }}>
          <TextLink onClick={drive.choose}>
            {drive.refusal !== null ? 'Erneut ermitteln' : 'OneDrive neu wählen'}
          </TextLink>
        </Box>
      )}
      {issue && (
        <Typography id={`${id}-error`} sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}>
          {issue}
        </Typography>
      )}
    </Box>
  )
}

/** Nothing to enter: the connected account names its OneDrive, the row shows which. */
export function OneDriveConnectionFields(props: SetupFieldsProps<OneDriveSourceValues>) {
  const drive = useOwnDrive(props)
  return <DriveState props={props} drive={drive} />
}

/** „Alle Ordner“ or the chosen folders, level by level in the shared dialog. */
export function OneDriveScopeFields(props: SetupFieldsProps<OneDriveSourceValues>) {
  const { values, onChange, fields, issueAt, fieldId } = props
  const drive = useOwnDrive(props)
  if (values.driveId === '' || drive.loading) return <DriveState props={props} drive={drive} />
  return (
    <ScopePicker
      id={fieldId('folders')}
      source={oneDriveSelectionSource(fields, values.driveId, oneDriveLabel(values))}
      value={oneDriveItemsOf(values.folders)}
      onChange={(items) => onChange({ folders: oneDriveFoldersOfItems(items, values.folders) })}
      all={{ active: values.allFolders, onChange: (allFolders) => onChange({ allFolders }) }}
      error={issueAt('folders')}
      labelOf={(item) => displayPath(item.name)}
      loadKey={JSON.stringify({ drive: values.driveId, probe: fields.probe })}
    />
  )
}
