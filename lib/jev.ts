// TypeSafe's Jev (https://docs.typesafe.ai): typed, calibrated judgments for calls our regexes
// can't make reliably — which competition a guide title is, whether two channel names are one
// channel. Only the offline scripts ask; the request path never waits on it. Billing is per
// input token, so callers batch questions (each question carries its own item — a shared state
// referenced by index measured less accurate) and cache every verdict.

export type JevQuestion =
  | { type: 'noul'; instructions: string }
  | { type: 'choice'; instructions: string; criteria: Record<string, string | null> }
export interface JevAnswer {
  choice?: string
  probabilities?: Record<string, number>
  noul?: number
}

// Thresholds were tuned on this version, so pin it rather than follow jev-latest.
const MODEL = 'jev-1.13.0'
let inputTokens = 0
/** Input tokens billed so far in this process ($0.042 per million). */
export const jevTokens = () => inputTokens

/**
 * Ask `questions` against a shared context, `perRequest` at a time (4 requests in flight).
 * Throws when TYPESAFE_API_KEY is unset or a batch keeps failing — callers fall back to rules.
 */
export async function askJev(
  state: string,
  questions: Record<string, JevQuestion>,
  perRequest = 36,
): Promise<Record<string, JevAnswer>> {
  const key = process.env.TYPESAFE_API_KEY
  if (!key) throw new Error('TYPESAFE_API_KEY not set')
  const ids = Object.keys(questions)
  const answers: Record<string, JevAnswer> = {}
  const ask = async (part: string[]) => {
    for (let attempt = 0; ; attempt++) {
      try {
        const resp = await fetch('https://api.typesafe.ai/v1/systemone', {
          method: 'POST',
          headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ model: MODEL, state, questions: Object.fromEntries(part.map((id) => [id, questions[id]])) }),
          signal: AbortSignal.timeout(20_000),
        })
        if (!resp.ok) throw new Error(`Jev HTTP ${resp.status}`)
        const body = (await resp.json()) as { answers: Record<string, JevAnswer>; usage?: { input_tokens?: number } }
        inputTokens += body.usage?.input_tokens ?? 0
        Object.assign(answers, body.answers)
        return
      } catch (e) {
        if (attempt >= 2) throw e
        await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt))
      }
    }
  }
  const parts: string[][] = []
  for (let i = 0; i < ids.length; i += perRequest) parts.push(ids.slice(i, i + perRequest))
  for (let i = 0; i < parts.length; i += 4) await Promise.all(parts.slice(i, i + 4).map(ask))
  return answers
}
