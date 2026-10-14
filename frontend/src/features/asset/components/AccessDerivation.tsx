import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  AccessPathResponse,
  AssetRole,
  AssetType,
  SpaceAccessDerivationResponse,
  SpaceRole,
} from '@/shared/api/types'
import { getAssetAccessDerivation } from '../api/assetApi'
import { accessBasisLabel } from './assetLabels'
import { assetRoleLabel, spaceRoleLabel } from '@/shared/utils/labels'
import { assetTypeLabel } from '../../../shared/assetTypes'

type AssetTarget = { kind: 'asset'; assetType: AssetType; assetId: string }
type SpaceTarget = { kind: 'space'; spaceId: string; userId?: string }
type DerivationTarget = AssetTarget | SpaceTarget

/** Loads the derivation of a space; without a user it explains the reading person's own access. */
export type SpaceDerivationLoader = (
  spaceId: string,
  userId?: string,
) => Promise<SpaceAccessDerivationResponse>

interface DerivationSubject {
  /** Whose access is explained; without a name the derivation addresses the reading person. */
  subjectName?: string | null
}

interface AssetDerivationProps extends DerivationSubject {
  target: AssetTarget
  load?: never
}

/** A space derivation is loaded by the caller: the asset theme cannot reach the space API. */
interface SpaceDerivationProps extends DerivationSubject {
  target: SpaceTarget
  load: SpaceDerivationLoader
}

type AccessDerivationProps = AssetDerivationProps | SpaceDerivationProps

function isSpaceDerivation(props: AccessDerivationProps): props is SpaceDerivationProps {
  return props.target.kind === 'space'
}

function loadDerivation(props: AccessDerivationProps) {
  const request = isSpaceDerivation(props)
    ? props.load(props.target.spaceId, props.target.userId)
    : getAssetAccessDerivation(props.target.assetType, props.target.assetId)
  return request.then((response) => ({
    paths: response.paths,
    role: response.effectiveRole ?? null,
    withheld: response.pathsWithheld,
  }))
}

/** Where a path comes from, lower-cased to continue the sentence after the dash. */
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

/** The first sentence without a full stop: who holds which effective role on the object. */
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

/** The path via the system administration on an asset: managing, not reading. */
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
 * „Warum hat … Zugriff?" / „Warum sehe ich …?" — the derivation of ADR-0036, Entscheidung 9:
 * one sentence with the effective role, with exactly one path including its origin; with several,
 * one line per path and the hint that the higher role applies. A path via a protected group that
 * concerns another person stays unnamed and still counts as a path.
 */
export default function AccessDerivation(props: AccessDerivationProps) {
  const { target, subjectName = null } = props
  const key =
    target.kind === 'asset'
      ? `asset:${target.assetType}:${target.assetId}`
      : `space:${target.spaceId}:${target.userId ?? 'self'}`
  // One state instead of four: as long as the response does not belong to the requested object, the
  // view is still loading - so the loading state needs no setState in the effect body.
  const [answer, setAnswer] = useState<{
    key: string
    paths: AccessPathResponse[]
    role: SpaceRole | AssetRole | null
    withheld: boolean
    error: string | null
  } | null>(null)

  useEffect(() => {
    let active = true
    void loadDerivation(props)
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
    // The key condenses the requested object; the object literal itself changes its identity
    // on every render and would otherwise trigger an endless loop of requests.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  if (answer?.key !== key) {
    return <Typography sx={{ color: 'text.secondary' }}>Herleitung wird geladen …</Typography>
  }
  if (answer.error) {
    return <Alert severity="error">{answer.error}</Alert>
  }

  const { withheld } = answer
  // On an asset the system administration manages without being allowed to read the content: its
  // path stands on its own, the effective role and „die höhere Rolle" follow only from the paths of
  // the formula.
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
