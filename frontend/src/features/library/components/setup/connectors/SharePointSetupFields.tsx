import { useState } from 'react'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { browseSource } from '../../../api/libraryApi'
import {
  SHAREPOINT_API,
  sharePointKeyOf,
  sharePointLibraryName,
  type SharePointSourceValues,
} from '../../../utils/sharePointSource'
import PickCombo, { type PickNode } from '../PickCombo'
import type { SetupFieldsProps } from '../setupTypes'
import {
  folderNode,
  libraryNode,
  sharePointChoices,
  sharePointNodes,
  type SharePointNodeData,
} from './sharePointNodes'

export function SharePointConnectionFields({ fields }: SetupFieldsProps<SharePointSourceValues>) {
  return (
    <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
      {fields.connection
        ? `Die Anmeldung läuft über den Zugang „${fields.connection.name}“. Sites und Dokumentbibliotheken wählen Sie im Umfang.`
        : 'Die Anmeldung läuft über den Zugang der Bibliothek.'}
    </Typography>
  )
}

/**
 * Site, then document libraries and folders: the site is searched by name or opened by its address,
 * its libraries list at the top level of the second box, folders below them.
 */
export function SharePointScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<SharePointSourceValues>) {
  const [site, setSite] = useState<PickNode | null>(null)
  const browse = (query: Record<string, unknown>) =>
    browseSource('SHAREPOINT', fields.probeRequest({ sourceUrl: SHAREPOINT_API, query }))
  return (
    <Stack spacing={2.5}>
      <PickCombo
        id={fieldId('site')}
        label="Site"
        hint="Nach dem Namen suchen oder die Adresse der Site einfügen."
        noun={['Site', 'Sites']}
        single
        serverSearch
        selected={site ? [site] : []}
        onChange={(nodes) => setSite(nodes[0] ?? null)}
        load={async (_parent, term) => {
          const address = /^https?:\/\//i.test(term.trim())
          const result = await browse(address ? { siteUrl: term.trim() } : { search: term.trim() })
          return {
            complete: result.complete,
            message: result.message,
            nodes: result.entries.flatMap((entry) => {
              const parsed = sharePointKeyOf(entry.key)
              return parsed?.kind === 'site'
                ? [{ key: parsed.id, label: entry.name ?? parsed.id, trail: [] }]
                : []
            }),
          }
        }}
        loadKey={JSON.stringify(fields.probe)}
        placeholder="Site suchen oder https://…/sites/… einfügen"
      />
      <PickCombo
        id={fieldId('libraries')}
        label="Dokumentbibliotheken und Ordner"
        hint="Eine ganze Dokumentbibliothek oder Ordner darin; Ordner: Pfeil am Eintrag oder Pfeiltaste rechts."
        error={issueAt('libraries')}
        noun={['Eintrag', 'Einträge']}
        selected={sharePointNodes(values.libraries)}
        onChange={(nodes) => onChange({ libraries: sharePointChoices(nodes) })}
        hierarchical
        max={50}
        load={async (parent) => {
          if (!site) throw new Error('Zuerst oben eine Site wählen.')
          if (!parent) {
            const result = await browse({ site: site.key })
            return {
              complete: result.complete,
              message: result.message,
              nodes: result.entries.flatMap((entry) => {
                const parsed = sharePointKeyOf(entry.key)
                return parsed?.kind === 'drive'
                  ? [
                      libraryNode(
                        parsed.id,
                        sharePointLibraryName(entry.name ?? null, site.label) ?? parsed.id,
                      ),
                    ]
                  : []
              }),
            }
          }
          const data = parent.data as SharePointNodeData
          const result = await browse({
            drive: data.driveId,
            ...(data.folderId ? { folder: data.folderId } : {}),
          })
          const trail = [...parent.trail, parent.label]
          return {
            complete: result.complete,
            message: result.message,
            nodes: result.entries.flatMap((entry) => {
              const parsed = sharePointKeyOf(entry.key)
              return parsed?.kind === 'folder'
                ? [folderNode(data.driveId, parsed.id, entry.name ?? parsed.id, trail)]
                : []
            }),
          }
        }}
        loadKey={JSON.stringify({ site: site?.key ?? null, probe: fields.probe })}
        placeholder={site ? 'Dokumentbibliothek suchen' : 'Zuerst eine Site wählen'}
      />
    </Stack>
  )
}
