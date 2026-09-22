import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { AccessPathResponse } from '../../types/api'
import { getLibraryAccessDerivation, getSpaceAccessDerivation } from '../../services/api'
import {
  accessBasisLabel,
  assetRoleLabel,
  groupMechanismLabel,
  groupOriginLabel,
  spaceRoleLabel,
} from '../../utils/labels'

interface AccessDerivationProps {
  /** Welches Objekt die Frage betrifft. */
  target:
    { kind: 'library'; libraryId: string } | { kind: 'space'; spaceId: string; userId?: string }
}

/** Ein Weg als Satz: Grundlage, Rolle, Gruppe mit Herkunft und Mechanismus, Zeitpunkt. */
function accessPathLine(path: AccessPathResponse): string {
  const parts = [accessBasisLabel(path.basis)]
  const role = path.assetRole
    ? assetRoleLabel(path.assetRole)
    : path.spaceRole
      ? spaceRoleLabel(path.spaceRole)
      : null
  if (role) parts.push(`Rolle ${role}`)
  if (path.group) {
    parts.push(
      `${path.group.name} (${groupOriginLabel({
        origin: path.group.origin,
        provider: path.group.providerName
          ? {
              id: '',
              displayName: path.group.providerName,
              external: false,
              enabled: true,
              groupMechanism: path.group.mechanism,
            }
          : null,
      })}, gepflegt über ${groupMechanismLabel(path.group.mechanism)})`,
    )
  }
  if (path.since) parts.push(`seit ${new Date(path.since).toLocaleDateString('de-DE')}`)
  return parts.join(' · ')
}

/**
 * „Warum sehe ich das?" — die Herleitung von ADR-0036, Entscheidung 9 (#1822): jeder Weg zur
 * wirksamen Rolle, ohne Vollmacht, ohne Protokoll. Die Mitglieder einer Gruppe werden dabei nie
 * genannt; führt ein Weg über eine geschützte Gruppe und geht die Auskunft um eine andere Person,
 * nennt die Antwort nur die wirksame Rolle.
 */
export default function AccessDerivation({ target }: AccessDerivationProps) {
  const key =
    target.kind === 'library'
      ? `library:${target.libraryId}`
      : `space:${target.spaceId}:${target.userId ?? 'self'}`
  // Ein Zustand statt vier: Solange die Antwort nicht zum angefragten Objekt gehört, lädt die
  // Ansicht noch - so kommt der Ladezustand ohne ein setState im Effektrumpf aus.
  const [answer, setAnswer] = useState<{
    key: string
    paths: AccessPathResponse[]
    role: string | null
    withheld: boolean
    error: string | null
  } | null>(null)

  useEffect(() => {
    let active = true
    const request =
      target.kind === 'library'
        ? getLibraryAccessDerivation(target.libraryId).then((response) => ({
            paths: response.paths,
            role: response.effectiveRole ? assetRoleLabel(response.effectiveRole) : null,
            withheld: response.pathsWithheld,
          }))
        : getSpaceAccessDerivation(target.spaceId, target.userId).then((response) => ({
            paths: response.paths,
            role: response.effectiveRole ? spaceRoleLabel(response.effectiveRole) : null,
            withheld: response.pathsWithheld,
          }))
    void request
      .then((response) => {
        if (active) setAnswer({ key, ...response, error: null })
      })
      .catch((err) => {
        if (!active) return
        setAnswer({
          key,
          paths: [],
          role: null,
          withheld: false,
          error: err instanceof Error ? err.message : 'Die Herleitung konnte nicht geladen werden.',
        })
      })
    return () => {
      active = false
    }
    // Der Schlüssel fasst das angefragte Objekt zusammen; das Objektliteral selbst wechselt bei
    // jedem Rendern die Identität und löste sonst eine Endlosschleife an Anfragen aus.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const isLoading = answer?.key !== key
  const error = answer?.error ?? null
  const paths = answer?.paths ?? []
  const effectiveRole = answer?.role ?? null
  const withheld = answer?.withheld ?? false

  if (isLoading) {
    return <Typography sx={{ color: 'text.secondary' }}>Herleitung wird geladen …</Typography>
  }
  if (error) {
    return <Alert severity="error">{error}</Alert>
  }

  return (
    <Stack spacing={0.5}>
      {effectiveRole && (
        <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>
          Wirksame Rolle: {effectiveRole}
        </Typography>
      )}
      {paths.length === 0 && !withheld && (
        <Typography sx={{ color: 'text.secondary' }}>Kein eigener Weg zu diesem Objekt.</Typography>
      )}
      <Stack component="ul" spacing={0.25} sx={{ m: 0, pl: 2.5 }}>
        {paths.map((path, index) => (
          <Typography
            component="li"
            key={`${path.basis}-${path.group?.id ?? index}`}
            sx={{ fontSize: 13 }}
          >
            {accessPathLine(path)}
          </Typography>
        ))}
      </Stack>
      {withheld && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          Ein Weg führt über eine geschützte Gruppe und wird hier nicht benannt.
        </Typography>
      )}
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        Die Mitglieder einer Gruppe werden hier nicht offengelegt.
      </Typography>
    </Stack>
  )
}
