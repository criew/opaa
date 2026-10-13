import { featureEnabledIn, useAuthStore } from '../stores/authStore'
import type { FeatureName } from '../types/auth'

/**
 * Whether an installation-wide feature is switched on (ADR-0044). Known before sign-in, because
 * the flags come with the public sign-in configuration; a flag the backend does not name counts
 * as on. Re-renders when the configuration is read again.
 */
export function useFeatureEnabled(name: FeatureName): boolean {
  return useAuthStore((state) => featureEnabledIn(state.features, name))
}
