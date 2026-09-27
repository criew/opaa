// Merges the OpenAPI fragments under opaa-api/src/main/openapi into one spec for openapi-typescript.
// Same rules as io.opaa.api.bundler.OpenApiBundler (the opaa-api Gradle build): root.yaml first,
// then every other *.yaml in name order; fragments hold only tags, paths and components; a key
// defined twice is an error, and so is any YAML anchor or alias. The Java bundler additionally
// validates $refs and tags. Works on YAML nodes: strings keep their quoting and block style, but
// numbers are re-emitted in canonical form (1e3 -> 1e+3). The CI job openapi-bundle-parity checks
// that both bundles load to the same tree.
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { Document, isAlias, isMap, isSeq, parseDocument, visit, YAMLMap, YAMLSeq } from 'yaml'

const [fragmentDir, outFile] = process.argv.slice(2)
if (!fragmentDir || !outFile) {
  throw new Error('usage: node bundle-openapi.mjs <fragmentDir> <outputFile>')
}

const ROOT = 'root.yaml'
const FRAGMENT_KEYS = new Set(['tags', 'paths', 'components'])

function load(file) {
  const doc = parseDocument(readFileSync(join(fragmentDir, file), 'utf8'), { intAsBigInt: true })
  if (doc.errors.length > 0) throw new Error(`${file}: ${doc.errors[0].message}`)
  visit(doc, {
    Node(_key, node) {
      if (isAlias(node) || node.anchor) {
        throw new Error(`${file}: YAML anchors and aliases are not allowed`)
      }
    },
  })
  if (!isMap(doc.contents)) throw new Error(`${file}: document must be a mapping`)
  return doc.contents
}

const head = []
const tags = new Map()
const paths = new Map()
const components = new Map()

function putAll(target, map, file, what) {
  if (!isMap(map)) throw new Error(`${file}: ${what} must be a mapping`)
  for (const pair of map.items) {
    const key = String(pair.key.value)
    if (target.has(key))
      throw new Error(`${file}: ${what} '${key}' is defined in more than one fragment`)
    target.set(key, pair)
  }
}

function merge(key, value, file) {
  if (key === 'tags') {
    if (!isSeq(value)) throw new Error(`${file}: 'tags' must be a list`)
    for (const item of value.items) {
      const name = String(item.get('name'))
      if (tags.has(name)) throw new Error(`${file}: tag '${name}' is already declared`)
      tags.set(name, item)
    }
  } else if (key === 'paths') {
    putAll(paths, value, file, 'path')
  } else {
    if (!isMap(value)) throw new Error(`${file}: components must be a mapping`)
    for (const kind of value.items) {
      const name = String(kind.key.value)
      if (!components.has(name)) components.set(name, new Map())
      putAll(components.get(name), kind.value, file, `components/${name}`)
    }
  }
}

for (const pair of load(ROOT).items) {
  const key = String(pair.key.value)
  if (FRAGMENT_KEYS.has(key)) merge(key, pair.value, ROOT)
  else head.push(pair)
}

const fragments = readdirSync(fragmentDir)
  .filter((f) => f.endsWith('.yaml') && f !== ROOT)
  .sort()
for (const file of fragments) {
  for (const pair of load(file).items) {
    const key = String(pair.key.value)
    if (!FRAGMENT_KEYS.has(key)) throw new Error(`${file}: top-level key '${key}' is not allowed`)
    merge(key, pair.value, file)
  }
}

const doc = new Document()
const out = new YAMLMap()
out.items.push(...head)
if (tags.size > 0) {
  const seq = new YAMLSeq()
  seq.items.push(...tags.values())
  out.set('tags', seq)
}
const pathMap = new YAMLMap()
pathMap.items.push(...paths.values())
out.set('paths', pathMap)
const componentMap = new YAMLMap()
for (const [kind, entries] of components) {
  const map = new YAMLMap()
  map.items.push(...entries.values())
  componentMap.set(kind, map)
}
out.set('components', componentMap)
doc.contents = out

mkdirSync(dirname(outFile), { recursive: true })
writeFileSync(outFile, doc.toString({ lineWidth: 0 }))
