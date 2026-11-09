import type { AssetType, Capability } from '../api/types'
import type { FeatureFlags, FeatureName } from './types'

/**
 * The installation-wide feature flags (ADR-0044) for modules that must not import a store - the
 * label tables, which the mocks import as well. `authStore` keeps this snapshot in step with its
 * own state; components read the store through `useFeatureEnabled`.
 */
let snapshot: FeatureFlags = {}

/** The agents switch as `agentAvailability` last recorded it; off until the backend says on. */
let agentsEnabled = false

/** Called by `authStore` on every change of its flags. */
export function recordInstallationFeatures(features: FeatureFlags): void {
  snapshot = features
}

/** Called by `agentAvailability` on every change of the agents switch. */
export function recordAgentsEnabled(enabled: boolean): void {
  agentsEnabled = enabled
}

/** The one rule every reader shares: a flag that is not named counts as on. */
export function featureEnabledIn(features: FeatureFlags, name: FeatureName): boolean {
  return features[name] !== false
}

/** The asset types that belong to a feature; switched off, the type is offered nowhere. */
export const ASSET_TYPE_FEATURES: Partial<Record<AssetType, FeatureName>> = {
  PROMPT_LIBRARY: 'promptLibraries',
}

/** The capabilities that belong to a feature; switched off, the right is neither held nor named. */
export const CAPABILITY_FEATURES: Partial<Record<Capability, FeatureName>> = {
  CREATE_PROMPT_LIBRARY: 'promptLibraries',
}

/** Whether the installation offers the asset type, by the given flags or the current ones. */
export function isAssetTypeOffered(
  assetType: AssetType | string | null | undefined,
  features: FeatureFlags = snapshot,
  agents: boolean = agentsEnabled,
): boolean {
  if (assetType === 'AGENT') return agents
  const feature = ASSET_TYPE_FEATURES[assetType as AssetType]
  return !feature || featureEnabledIn(features, feature)
}

/** Whether the installation offers the capability right now. */
export function isCapabilityOffered(capability: Capability): boolean {
  if (capability === 'CREATE_AGENT') return agentsEnabled
  const feature = CAPABILITY_FEATURES[capability]
  return !feature || featureEnabledIn(snapshot, feature)
}
