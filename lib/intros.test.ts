import { expect, test } from 'bun:test'
import { segmentsOf } from './intros.js'

test("an episode's intro, recap and credits merge TheIntroDB's answer with IntroDB's, placeholders dropped", () => {
  // TheIntroDB's real shape (tmdb 82728 s2e3): a null intro start is the first frame, a 0–0 recap means none, credits run to the end.
  expect(segmentsOf({ intro: [{ start_ms: null, end_ms: 26068 }], recap: [{ start_ms: 0, end_ms: 0 }], credits: [{ start_ms: 405000, end_ms: null }] }, null))
    .toEqual({ intro: [0, 26068], credits: [405000, 0] })
  // IntroDB fills what TheIntroDB lacks; its outro is the credits.
  expect(segmentsOf({ intro: [{ start_ms: 314528, end_ms: 331101 }] }, { intro: null, recap: { start_ms: 0, end_ms: 42000 }, outro: { start_ms: 2843000, end_ms: 2901000 } }))
    .toEqual({ intro: [314528, 331101], recap: [0, 42000], credits: [2843000, 2901000] })
  expect(segmentsOf(null, null)).toEqual({})
})
