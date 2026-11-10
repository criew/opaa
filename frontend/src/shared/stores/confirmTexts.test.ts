import ts from 'typescript'
import { describe, expect, it } from 'vitest'

/**
 * Every confirmation follows the rules of design guidelines 5.10 and 6. The texts are read from
 * the source, so a call that is never rendered in a test is checked as well.
 */
const SOURCES = import.meta.glob('../../**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUCTION = Object.entries(SOURCES).filter(
  ([path]) => !path.includes('.test.') && !path.includes('/mocks/') && !path.endsWith('.d.ts'),
)

/** Verbs the guidelines replaced (section 6) and labels that do not name the action (5.10). */
const RETIRED_VERBS = /(^|[^\p{L}])(abschalten|lösen|entziehen|beenden)([^\p{L}]|$)/iu
const VAGUE_LABELS = /^(ok|ja|weiter|fortfahren|bestätigen)([^\p{L}]|$)/iu
/** The type of the object in front of its name: „<Name>“ <Verb>? names the object alone. */
const OBJECT_TYPE_PREFIX =
  /^((den|die|das|dem|der)\s+)?(Bibliothek|Prompt-Bibliothek|Prompt|Vorlage|Modell|Dokument|Ordner|Gruppe|Token|Zugang|Quellart|Dienstkonto|Anhang|Chat|Space|Konto)\s+„/i
const STOCK_CONSEQUENCE = /kann nicht rückgängig gemacht werden/i
const PLACEHOLDER = '…'

/**
 * Calls whose texts are partly built at run time (a helper, JSX, a parameter) and so are checked
 * only in the parts written in the source, counted per file. A new call of that kind must be
 * added here on purpose, so that it cannot slip past the checks unnoticed.
 */
const UNRESOLVED_PER_FILE: Record<string, number> = {
  'features/connection/components/ConnectedAccountsSection.tsx': 1,
  'features/connection/components/ConnectionProfileFormDialog.tsx': 1,
  'features/group/components/groupDeactivation.ts': 1,
  'features/model/components/EmbeddingChangeSection.tsx': 3,
  'features/principal/components/subjectSelection.ts': 4,
  'shared/ui/FactoryDefaultNumberSetting.tsx': 2,
}

interface ConfirmCall {
  where: string
  file: string
  questions: string[] | null
  consequences: string[] | null
  hasConsequence: boolean
  hasLabel: boolean
  labels: string[] | null
}

function parse(path: string, text: string) {
  return ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
}

/** `relative`, seen from the file `from`; both relative to `src/`. */
function resolvePath(from: string, relative: string): string {
  const parts = from.split('/').slice(0, -1)
  for (const part of relative.split('/')) {
    if (part === '..') parts.pop()
    else if (part !== '.') parts.push(part)
  }
  return parts.join('/')
}

/** Paths relative to `src/`, as an import with `@/` names them. */
const FILES = PRODUCTION.map(([path, text]) => ({
  path: resolvePath('shared/stores/confirmTexts.test.ts', path),
  file: parse(path, text),
}))
const BY_PATH = new Map(FILES.map(({ path, file }) => [path, file]))

/**
 * The constants a file declares, under their name; inner scopes included. A name declared twice in
 * one file stays unresolved, so its call counts in {@link UNRESOLVED_PER_FILE}.
 */
function declarations(file: ts.SourceFile): Map<string, ts.Expression | undefined> {
  const found = new Map<string, ts.Expression | undefined>()
  ts.forEachChild(file, function visit(node) {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      const name = node.name.text
      found.set(name, found.has(name) ? undefined : node.initializer)
    }
    ts.forEachChild(node, visit)
  })
  return found
}
const DECLARATIONS = new Map(FILES.map(({ path, file }) => [path, declarations(file)]))

function resolveModule(from: string, specifier: string): string | undefined {
  let base: string
  if (specifier.startsWith('@/')) base = specifier.slice(2)
  else if (specifier.startsWith('.')) base = resolvePath(from, specifier)
  else return undefined
  return [base, `${base}.ts`, `${base}.tsx`, `${base}/index.ts`].find((p) => BY_PATH.has(p))
}

/** A name in `path`: declared there, or imported by name from another source file. */
function lookup(path: string, name: string, depth: number): ts.Expression | undefined {
  const declared = DECLARATIONS.get(path)
  if (declared?.has(name) || depth > 4) return declared?.get(name)
  const file = BY_PATH.get(path)
  for (const statement of file?.statements ?? []) {
    if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier))
      continue
    const bindings = statement.importClause?.namedBindings
    if (!bindings || !ts.isNamedImports(bindings)) continue
    const element = bindings.elements.find((e) => e.name.text === name)
    if (!element) continue
    const target = resolveModule(path, statement.moduleSpecifier.text)
    if (!target) return undefined
    return lookup(target, (element.propertyName ?? element.name).text, depth + 1)
  }
  return undefined
}

/** Whether `verb` occurs in `text` as a whole word. */
function containsWord(text: string, verb: string): boolean {
  const escaped = verb.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(^|[^\\p{L}])${escaped}([^\\p{L}]|$)`, 'iu').test(text)
}

/**
 * The texts an expression can take, with every interpolation as „…“; `null` when a part is not
 * written in the source (a parameter, a call).
 */
function texts(node: ts.Expression, path: string, depth = 0): string[] | null {
  if (depth > 8) return null
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return [node.text]
  if (ts.isTemplateExpression(node)) {
    return [node.head.text + node.templateSpans.map((s) => PLACEHOLDER + s.literal.text).join('')]
  }
  if (ts.isParenthesizedExpression(node)) return texts(node.expression, path, depth + 1)
  if (ts.isConditionalExpression(node)) {
    const a = texts(node.whenTrue, path, depth + 1)
    const b = texts(node.whenFalse, path, depth + 1)
    return a && b ? [...a, ...b] : null
  }
  if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const a = texts(node.left, path, depth + 1)
    const b = texts(node.right, path, depth + 1)
    return a && b ? a.flatMap((x) => b.map((y) => x + y)) : null
  }
  if (ts.isIdentifier(node)) {
    const value = lookup(path, node.text, 0)
    return value ? texts(value, path, depth + 1) : null
  }
  return null
}

function property(object: ts.ObjectLiteralExpression, name: string): ts.Expression | undefined {
  for (const element of object.properties) {
    if (ts.isPropertyAssignment(element) && element.name.getText() === name) {
      return element.initializer
    }
    if (ts.isShorthandPropertyAssignment(element) && element.name.text === name) {
      return element.name
    }
  }
  return undefined
}

/**
 * Every argument of `confirmAction` and every object that hands question and consequence on to
 * one (`confirmChange` of FactoryDefaultNumberSetting).
 */
function confirmCalls(): ConfirmCall[] {
  const calls: ConfirmCall[] = []
  for (const { path, file } of FILES) {
    ts.forEachChild(file, function visit(node) {
      let object: ts.ObjectLiteralExpression | undefined
      if (
        ts.isCallExpression(node) &&
        ts.isIdentifier(node.expression) &&
        node.expression.text === 'confirmAction' &&
        node.arguments[0] &&
        ts.isObjectLiteralExpression(node.arguments[0])
      ) {
        object = node.arguments[0]
      } else if (
        ts.isObjectLiteralExpression(node) &&
        property(node, 'question') &&
        property(node, 'consequence') &&
        !(ts.isCallExpression(node.parent) && node.parent.expression.getText() === 'confirmAction')
      ) {
        object = node
      }
      if (object) {
        const question = property(object, 'question')
        const consequence = property(object, 'consequence')
        const label = property(object, 'confirmLabel')
        const { line } = file.getLineAndCharacterOfPosition(object.getStart())
        calls.push({
          where: `${path}:${line + 1}`,
          file: path,
          questions: question ? texts(question, path) : null,
          consequences: consequence ? texts(consequence, path) : null,
          hasConsequence: consequence !== undefined,
          hasLabel: label !== undefined,
          labels: label ? texts(label, path) : null,
        })
      }
      ts.forEachChild(node, visit)
    })
  }
  return calls
}

const CALLS = confirmCalls()

function violations(check: (call: ConfirmCall) => string | null): string[] {
  return CALLS.map((call) => {
    const problem = check(call)
    return problem ? `${call.where}: ${problem}` : null
  }).filter((entry): entry is string => entry !== null)
}

describe('confirmation texts (guidelines 5.10 and 6)', () => {
  it('finds the confirmations in the source', () => {
    expect(CALLS.length).toBeGreaterThan(80)
  })

  it('reads every text from the source, except the calls listed as built at run time', () => {
    const unresolved: Record<string, number> = {}
    for (const call of CALLS) {
      const open =
        call.questions === null ||
        (call.hasConsequence && call.consequences === null) ||
        (call.hasLabel && call.labels === null)
      if (open) unresolved[call.file] = (unresolved[call.file] ?? 0) + 1
    }
    expect(unresolved).toEqual(UNRESOLVED_PER_FILE)
  })

  it('asks „<Name>“ <Verb>? with typographic quotes, without object type and „wirklich“', () => {
    expect(
      violations(({ questions }) => {
        for (const q of questions ?? []) {
          if (!q.trim().endsWith('?')) return `no question mark: ${q}`
          if (q.includes('"')) return `straight quotes: ${q}`
          if (/\bwirklich\b/i.test(q)) return `„wirklich“: ${q}`
          if (OBJECT_TYPE_PREFIX.test(q)) return `object type before the name: ${q}`
          if (RETIRED_VERBS.test(q)) return `retired verb: ${q}`
          if (q.includes('„') !== q.includes('“')) return `unbalanced quotes: ${q}`
        }
        return null
      }),
    ).toEqual([])
  })

  it('names a consequence, never only that it cannot be undone', () => {
    expect(
      violations(({ hasConsequence, consequences }) => {
        if (!hasConsequence) return 'no consequence'
        for (const c of consequences ?? []) {
          if (c.trim() === '') return 'empty consequence'
          if (STOCK_CONSEQUENCE.test(c)) return `stock sentence instead of the loss: ${c}`
          if (c.includes('"')) return `straight quotes: ${c}`
        }
        return null
      }),
    ).toEqual([])
  })

  it('labels the button with the verb of the question', () => {
    expect(
      violations(({ labels, questions }) => {
        for (const label of labels ?? []) {
          if (VAGUE_LABELS.test(label)) return `label does not name the action: ${label}`
          if (RETIRED_VERBS.test(label)) return `retired verb: ${label}`
          if (questions && !questions.some((q) => containsWord(q, label))) {
            return `„${label}“ is not the verb of the question: ${questions.join(' | ')}`
          }
        }
        return null
      }),
    ).toEqual([])
  })
})
