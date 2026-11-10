import type { AssetType, Capability } from '../api/types'
import type { FeatureFlags, FeatureName } from './types'

/**
 * The installation-wide feature flags (ADR-0044) for modules that must not import a store - the
 * label tables, which the mocks import as well. `authStore` keeps this snapshot in step with its
 * own state; components read the store through `useFeatureEnabled`.
 */
let snapshot: FeatureFlags = {}

/** The installation switches changed while the installation runs, see `runtimeSwitches.ts`. */
export interface RuntimeSwitches {
  /** The switch "Vorlagen": document templates are offered. */
  documentTemplates: boolean
  /** The switch "Agenten": agents are offered. */
  agents: boolean
}

let runtimeSnapshot: RuntimeSwitches = { documentTemplates: false, agents: false }

/** Called by the stores behind the runtime switches on every change, each with its own switches. */
export function recordRuntimeSwitches(switches: Partial<RuntimeSwitches>): void {
  runtimeSnapshot = { ...runtimeSnapshot, ...switches }
}

/** The asset types behind a runtime switch; off (or not yet read), the type is offered nowhere. */
export const ASSET_TYPE_RUNTIME_SWITCHES: Partial<Record<AssetType, keyof RuntimeSwitches>> = {
  DOCUMENT_TEMPLATE: 'documentTemplates',
  AGENT: 'agents',
}

/** The capabilities behind a runtime switch; off, the right is neither held nor named. */
export const CAPABILITY_RUNTIME_SWITCHES: Partial<Record<Capability, keyof RuntimeSwitches>> = {
  CREATE_TEMPLATE: 'documentTemplates',
  CREATE_AGENT: 'agents',
}

/** Called by `authStore` on every change of its flags. */
export function recordInstallationFeatures(features: FeatureFlags): void {
  snapshot = features
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
  switches: Partial<RuntimeSwitches> = runtimeSnapshot,
): boolean {
  const feature = ASSET_TYPE_FEATURES[assetType as AssetType]
  const runtimeSwitch = ASSET_TYPE_RUNTIME_SWITCHES[assetType as AssetType]
  return (
    (!feature || featureEnabledIn(features, feature)) &&
    (!runtimeSwitch || switches[runtimeSwitch] === true)
  )
}

/** Whether the installation offers the capability right now. */
export function isCapabilityOffered(capability: Capability): boolean {
  const feature = CAPABILITY_FEATURES[capability]
  const runtimeSwitch = CAPABILITY_RUNTIME_SWITCHES[capability]
  return (
    (!feature || featureEnabledIn(snapshot, feature)) &&
    (!runtimeSwitch || runtimeSnapshot[runtimeSwitch])
  )
}
