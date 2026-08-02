import type { WorkspaceRole, WorkspaceType } from '../types/api'
import type { AccessLevel } from '../types/chat'

const workspaceRoleLabels: Record<WorkspaceRole, string> = {
  VIEWER: 'Leser',
  EDITOR: 'Bearbeiter',
  ADMIN: 'Administrator',
  OWNER: 'Eigentümer',
}

const workspaceTypeLabels: Record<WorkspaceType, string> = {
  PERSONAL: 'Persönlich',
  SHARED: 'Geteilt',
}

export function workspaceRoleLabel(role: WorkspaceRole | string | undefined): string {
  if (!role) return ''
  return workspaceRoleLabels[role as WorkspaceRole] ?? role
}

export function workspaceTypeLabel(type: WorkspaceType | string | undefined): string {
  if (!type) return ''
  return workspaceTypeLabels[type as WorkspaceType] ?? type
}

const accessLevelLabels: Record<AccessLevel, string> = {
  Public: 'Öffentlich',
  Internal: 'Intern',
  Confidential: 'Vertraulich',
}

export function accessLevelLabel(level: AccessLevel): string {
  return accessLevelLabels[level]
}
