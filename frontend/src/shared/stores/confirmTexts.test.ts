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
const RETIRED_VERBS = /\b(abschalten|lösen|entziehen|beenden)\b/i
const VAGUE_LABELS = /^(ok|ja|weiter|fortfahren|bestätigen)\b/i
/** The type of the object in front of its name: „<Name>“ <Verb>? names the object alone. */
const OBJECT_TYPE_PREFIX =
  /^(Bibliothek|Prompt-Bibliothek|Prompt|Vorlage|Modell|Dokument|Ordner|Gruppe|Token|Zugang|Dienstkonto|Anhang|Chat|Space|Konto)\s+„/
const STOCK_CONSEQUENCE = /kann nicht rückgängig gemacht werden/i
const PLACEHOLDER = '…'

interface ConfirmCall {
  where: string
  questions: string[] | null
  consequences: string[] | null
  hasConsequence: boolean
  labels: string[] | null
}

function parse(path: string, text: string) {
  return ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
}

const FILES = PRODUCTION.map(([path, text]) => ({ path, file: parse(path, text) }))

/** Top-level constants of all files; a name declared twice resolves to all its values. */
const CONSTANTS = new Map<string, ts.Expression[]>()
for (const { file } of FILES) {
  ts.forEachChild(file, function visit(node) {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      const list = CONSTANTS.get(node.name.text) ?? []
      list.push(node.initializer)
      CONSTANTS.set(node.name.text, list)
    }
    ts.forEachChild(node, visit)
  })
}

/**
 * The texts an expression can take, with every interpolation as „…“; `null` when a part is not
 * written in the source (a parameter, a call).
 */
function texts(node: ts.Expression, depth = 0): string[] | null {
  if (depth > 8) return null
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return [node.text]
  if (ts.isTemplateExpression(node)) {
    return [node.head.text + node.templateSpans.map((s) => PLACEHOLDER + s.literal.text).join('')]
  }
  if (ts.isParenthesizedExpression(node)) return texts(node.expression, depth + 1)
  if (ts.isConditionalExpression(node)) {
    const a = texts(node.whenTrue, depth + 1)
    const b = texts(node.whenFalse, depth + 1)
    return a && b ? [...a, ...b] : null
  }
  if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const a = texts(node.left, depth + 1)
    const b = texts(node.right, depth + 1)
    return a && b ? a.flatMap((x) => b.map((y) => x + y)) : null
  }
  if (ts.isIdentifier(node)) {
    const values = CONSTANTS.get(node.text)
    if (!values) return null
    const resolved = values.map((value) => texts(value, depth + 1))
    return resolved.every((r) => r !== null) ? resolved.flat() : null
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
          where: `${path.replace(/^(\.\.\/)+/, '')}:${line + 1}`,
          questions: question ? texts(question) : null,
          consequences: consequence ? texts(consequence) : null,
          hasConsequence: consequence !== undefined,
          labels: label ? texts(label) : null,
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
          const verb = label.toLocaleLowerCase('de')
          if (questions && !questions.some((q) => q.toLocaleLowerCase('de').includes(verb))) {
            return `„${label}“ is not the verb of the question: ${questions.join(' | ')}`
          }
        }
        return null
      }),
    ).toEqual([])
  })
})
