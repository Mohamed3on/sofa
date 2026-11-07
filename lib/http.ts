// The box's JSON fetch for outside APIs (TMDB, Torrentio, …): a timeout, a browser User-Agent (Torrentio
// refuses bare clients), and askJev's retry shape. A 4xx other than 429 is the answer, not a hiccup:
// it fails at once. Errors name the status only, never the URL (keys ride in query strings).

const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'

export class HttpError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`)
  }
}

export async function getJson<T>(url: string, { timeoutMs = 10_000, retries = 2, headers = {} as Record<string, string> } = {}): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      const resp = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json', ...headers }, signal: AbortSignal.timeout(timeoutMs) })
      if (!resp.ok) throw new HttpError(resp.status)
      return (await resp.json()) as T
    } catch (e) {
      if (attempt >= retries || (e instanceof HttpError && e.status < 500 && e.status !== 429)) throw e
      await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt))
    }
  }
}
