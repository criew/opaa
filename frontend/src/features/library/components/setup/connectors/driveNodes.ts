import {
  googleDriveScopeKey,
  googleDriveScopeKindLabel,
  googleDriveScopeLabel,
  type GoogleDriveScope,
} from '../../../utils/googleDriveSource'
import type { PickNode } from '../PickCombo'

export function driveNode(scope: GoogleDriveScope): PickNode {
  return {
    key: googleDriveScopeKey(scope),
    label: googleDriveScopeLabel(scope),
    trail: [],
    secondary: scope.name
      ? googleDriveScopeKindLabel(scope.kind)
      : `${googleDriveScopeKindLabel(scope.kind)} · ID`,
    data: scope,
  }
}

export function driveScopeOf(node: PickNode): GoogleDriveScope {
  return node.data as GoogleDriveScope
}
