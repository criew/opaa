/** Where the handbook is published: main under the root, every release under v<version>/. */
export const HANDBOOK_BASE_URL = 'https://opaa.io/handbuch/'

/**
 * The handbook version that belongs to a build: a release (X.Y.Z[-<pre>]) has its own, every
 * other build - 0.0.0-* for development, smoke tests and main images - the current state of main.
 */
export function handbookVersionPath(version: string): string {
  return /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version) && !version.startsWith('0.0.0-')
    ? `v${version}/`
    : ''
}

/** The address of a handbook page (`betrieb/installation.html`) in the handbook of `version`. */
export function handbookPageUrl(page: string, version: string): string {
  return `${HANDBOOK_BASE_URL}${handbookVersionPath(version)}${page}`
}
