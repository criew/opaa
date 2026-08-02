import type { SpaceKind, SpaceRole } from '../types/api'
import type { AccessLevel } from '../types/chat'

const spaceRoleLabels: Record<SpaceRole, string> = {
  MEMBER: 'Mitglied',
  CURATOR: 'Kurator',
  ADMIN: 'Administrator',
}

const spaceKindLabels: Record<SpaceKind, string> = {
  PERSONAL: 'Persönlich',
  PROJECT: 'Projekt',
  TEAM: 'Team',
}

export function spaceRoleLabel(role: SpaceRole | string | undefined): string {
  if (!role) return ''
  return spaceRoleLabels[role as SpaceRole] ?? role
}

export function spaceKindLabel(kind: SpaceKind | string | undefined): string {
  if (!kind) return ''
  return spaceKindLabels[kind as SpaceKind] ?? kind
}

const accessLevelLabels: Record<AccessLevel, string> = {
  Public: 'Öffentlich',
  Internal: 'Intern',
  Confidential: 'Vertraulich',
}

export function accessLevelLabel(level: AccessLevel): string {
  return accessLevelLabels[level]
}
