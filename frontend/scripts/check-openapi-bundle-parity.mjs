// Fails unless two OpenAPI bundles load to the same value tree under YAML 1.1 - the resolution
// rules of SnakeYAML, which the backend generator and tests use. Compares the Gradle bundle
// (io.opaa.api.bundler.OpenApiBundler) with the frontend bundle (scripts/bundle-openapi.mjs).
// Mappings load as Map, so a key keeps its type: 200 and "200" are different keys.
import { readFileSync } from 'node:fs'
import { isDeepStrictEqual } from 'node:util'
import { parseDocument } from 'yaml'

const [first, second] = process.argv.slice(2)
if (!first || !second) {
  throw new Error('usage: node check-openapi-bundle-parity.mjs <bundleA> <bundleB>')
}

function load(file) {
  const doc = parseDocument(readFileSync(file, 'utf8'), { version: '1.1', intAsBigInt: true })
  if (doc.errors.length > 0) throw new Error(`${file}: ${doc.errors[0].message}`)
  return doc.toJS({ mapAsMap: true, maxAliasCount: -1 })
}

const show = (value) => (typeof value === 'string' ? JSON.stringify(value) : String(value))

function firstDifference(a, b, path) {
  if (isDeepStrictEqual(a, b)) return null
  if (a instanceof Map && b instanceof Map) {
    for (const key of a.keys()) {
      if (!b.has(key)) return `${path}: key ${show(key)} only in the first bundle`
    }
    for (const key of b.keys()) {
      if (!a.has(key)) return `${path}: key ${show(key)} only in the second bundle`
    }
    for (const key of a.keys()) {
      const found = firstDifference(a.get(key), b.get(key), `${path}/${String(key)}`)
      if (found) return found
    }
  }
  if (Array.isArray(a) && Array.isArray(b) && a.length === b.length) {
    for (let i = 0; i < a.length; i++) {
      const found = firstDifference(a[i], b[i], `${path}/${i}`)
      if (found) return found
    }
  }
  return `${path}: ${show(a)} != ${show(b)}`
}

const difference = firstDifference(load(first), load(second), '#')
if (difference) {
  console.error(`OpenAPI bundles differ at ${difference}`)
  process.exit(1)
}
console.log('OpenAPI bundles are identical')
