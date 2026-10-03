import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { AccessPathResponse, AssetRole, AssetType, SpaceRole } from '../../types/api'
import { getAssetAccessDerivation } from '../../services/assetApi'
import { getSpaceAccessDerivation } from '../../services/spaceApi'
import { accessBasisLabel, assetRoleLabel, spaceRoleLabel } from '../../utils/labels'
import { assetTypeLabel } from '../assets/assetTypeRegistry'

type DerivationTarget =
  | { kind: 'asset'; assetType: AssetType; assetId: string }
  | { kind: 'space'; spaceId: string; userId?: string }

interface AccessDerivationProps {
  /** Welches Objekt die Frage betrifft. */
  target: DerivationTarget
  /** Wessen Zugriff erklärt wird; ohne Namen spricht die Herleitung die lesende Person an. */
  subjectName?: string | null
}

/** Woher ein Weg kommt, klein geschrieben für den Satzanschluss nach dem Gedankenstrich. */
function originOf(path: AccessPathResponse): string {
  switch (path.basis) {
    case 'DIRECT_MEMBERSHIP':
      return 'direkt aufgenommen'
    case 'DIRECT_GRANT':
      return 'direkt freigegeben'
    case 'GROUP_MEMBERSHIP':
    case 'GROUP_GRANT':
      return path.group ? `über die Gruppe ${path.group.name}` : 'über eine Gruppe'
    case 'ORGANIZATION_WIDE':
      return 'für alle Konten freigegeben'
    case 'OWNERSHIP':
      return 'als Eigentümer'
    case 'SYSTEM_ADMINISTRATION':
      return 'über die Systemverwaltung'
    default:
      return accessBasisLabel(path.basis)
  }
}

function capitalized(text: string): string {
  return text.charAt(0).toLocaleUpperCase('de') + text.slice(1)
}

function pathRoleLabel(path: AccessPathResponse): string | null {
  if (path.assetRole) return assetRoleLabel(path.assetRole)
  if (path.spaceRole) return spaceRoleLabel(path.spaceRole)
  return null
}

function sinceTitle(path: AccessPathResponse): string | undefined {
  return path.since ? `Seit ${new Date(path.since).toLocaleDateString('de-DE')}` : undefined
}

const assetRolePhrases: Record<AssetRole, (noun: string) => [string, string]> = {
  VIEWER: (noun) => ['darf', `diese ${noun} lesen`],
  EDITOR: (noun) => ['darf', `diese ${noun} bearbeiten`],
  MANAGER: (noun) => ['darf', `diese ${noun} verwalten`],
  OWNER: (noun) => ['ist', `Eigentümer dieser ${noun}`],
}

const pluralVerbs: Record<string, string> = { darf: 'dürfen', ist: 'sind', hat: 'haben' }

/** Der erste Satz ohne Schlusspunkt: wer welche wirksame Rolle am Objekt hat. */
function headline(
  target: DerivationTarget,
  subjectName: string | null,
  role: SpaceRole | AssetRole | null,
): string {
  const [verb, rest] =
    target.kind === 'space'
      ? role
        ? ['ist', `${spaceRoleLabel(role)} in diesem Space`]
        : ['hat', 'Zugriff auf diesen Space']
      : (assetRolePhrases[role as AssetRole]?.(assetTypeLabel(target.assetType)) ?? [
          'hat',
          `Zugriff auf diese ${assetTypeLabel(target.assetType)}`,
        ])
  return subjectName ? `${subjectName} ${verb} ${rest}` : `Sie ${pluralVerbs[verb]} ${rest}`
}

const assetRoleRank: Record<AssetRole, number> = { VIEWER: 0, EDITOR: 1, MANAGER: 2, OWNER: 3 }

function highestAssetRole(paths: AccessPathResponse[]): AssetRole | null {
  return paths.reduce<AssetRole | null>((highest, path) => {
    const role = path.assetRole ?? null
    if (!role) return highest
    return !highest || assetRoleRank[role] > assetRoleRank[highest] ? role : highest
  }, null)
}

/** Der Weg über die Systemverwaltung an einem Asset: verwalten, nicht lesen. */
function administrationSentence(
  assetType: AssetType,
  subjectName: string | null,
  besideOtherWays: boolean,
): string {
  const subject = subjectName ? `${subjectName} verwaltet` : 'Sie verwalten'
  const also = besideOtherWays ? ' zudem' : ''
  return `${subject} diese ${assetTypeLabel(assetType)}${also} über die Systemverwaltung (ohne Leserecht am Inhalt).`
}

/**
 * „Warum hat … Zugriff?" / „Warum sehe ich …?" — die Herleitung von ADR-0036, Entscheidung 9:
 * ein Satz mit der wirksamen Rolle, bei genau einem Weg samt Herkunft; bei mehreren eine Zeile je
 * Weg und der Hinweis, dass die höhere Rolle gilt. Ein Weg über eine geschützte Gruppe, die eine
 * andere Person betrifft, bleibt ungenannt und zählt dennoch als Weg.
 */
export default function AccessDerivation({ target, subjectName = null }: AccessDerivationProps) {
  const key =
    target.kind === 'asset'
      ? `asset:${target.assetType}:${target.assetId}`
      : `space:${target.spaceId}:${target.userId ?? 'self'}`
  // Ein Zustand statt vier: Solange die Antwort nicht zum angefragten Objekt gehört, lädt die
  // Ansicht noch - so kommt der Ladezustand ohne ein setState im Effektrumpf aus.
  const [answer, setAnswer] = useState<{
    key: string
    paths: AccessPathResponse[]
    role: SpaceRole | AssetRole | null
    withheld: boolean
    error: string | null
  } | null>(null)

  useEffect(() => {
    let active = true
    const request =
      target.kind === 'asset'
        ? getAssetAccessDerivation(target.assetType, target.assetId).then((response) => ({
            paths: response.paths,
            role: response.effectiveRole ?? null,
            withheld: response.pathsWithheld,
          }))
        : getSpaceAccessDerivation(target.spaceId, target.userId).then((response) => ({
            paths: response.paths,
            role: response.effectiveRole ?? null,
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

  if (answer?.key !== key) {
    return <Typography sx={{ color: 'text.secondary' }}>Herleitung wird geladen …</Typography>
  }
  if (answer.error) {
    return <Alert severity="error">{answer.error}</Alert>
  }

  const { withheld } = answer
  // Am Asset verwaltet die Systemverwaltung, ohne den Inhalt lesen zu dürfen: Ihr Weg steht für
  // sich, die wirksame Rolle und „die höhere Rolle" folgen nur aus den Wegen der Formel.
  const administration =
    target.kind === 'asset' && answer.paths.some((path) => path.basis === 'SYSTEM_ADMINISTRATION')
  const paths = administration
    ? answer.paths.filter((path) => path.basis !== 'SYSTEM_ADMINISTRATION')
    : answer.paths
  const role = administration ? highestAssetRole(paths) : answer.role
  const administrationLine =
    administration && target.kind === 'asset' ? (
      <Typography sx={{ fontSize: 13.5 }}>
        {administrationSentence(target.assetType, subjectName, paths.length > 0 || withheld)}
      </Typography>
    ) : null

  if (paths.length === 0 && !withheld) {
    return (
      administrationLine ?? (
        <Typography sx={{ color: 'text.secondary' }}>Kein eigener Weg zu diesem Objekt.</Typography>
      )
    )
  }

  const first = headline(target, subjectName, role)
  const wayCount = paths.length + (withheld ? 1 : 0)
  if (wayCount === 1 && paths.length === 1) {
    return (
      <Stack spacing={0.25}>
        <Typography sx={{ fontSize: 13.5 }} title={sinceTitle(paths[0])}>
          {`${first} – ${originOf(paths[0])}.`}
        </Typography>
        {administrationLine}
      </Stack>
    )
  }

  return (
    <Stack spacing={0.25}>
      <Typography sx={{ fontSize: 13.5 }}>{`${first}.`}</Typography>
      {paths.map((path, index) => {
        const pathRole = pathRoleLabel(path)
        const origin = capitalized(originOf(path))
        return (
          <Typography
            key={`${path.basis}-${path.group?.id ?? index}`}
            title={sinceTitle(path)}
            sx={{ fontSize: 13, pl: 2 }}
          >
            {pathRole ? `${origin}: ${pathRole}` : origin}
          </Typography>
        )
      })}
      {withheld && (
        <Typography sx={{ fontSize: 13, pl: 2, color: 'text.secondary' }}>
          Ein Weg führt über eine geschützte Gruppe und wird hier nicht benannt.
        </Typography>
      )}
      {wayCount > 1 && <Typography sx={{ fontSize: 13 }}>Es gilt die höhere Rolle.</Typography>}
      {administrationLine}
    </Stack>
  )
}
