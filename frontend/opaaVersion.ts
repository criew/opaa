/** Version of every build that is not given one, main images and local builds included. */
export const DEVELOPMENT_VERSION = '0.0.0-dev'

// The form deploy/helm/ci/release-version.sh prints for a release tag; follows a change there.
const RELEASE_VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?$/

/**
 * The version the frontend build reports, from the build argument OPAA_VERSION: empty or missing
 * means the development state, anything else must be X.Y.Z or X.Y.Z-<pre> or the build fails.
 */
export function resolveOpaaVersion(raw: string | undefined): string {
  const version = raw || DEVELOPMENT_VERSION
  if (!RELEASE_VERSION.test(version)) {
    throw new Error(
      `OPAA_VERSION '${raw}' is not a version X.Y.Z or X.Y.Z-<pre> (docs/releases.md)`,
    )
  }
  return version
}
