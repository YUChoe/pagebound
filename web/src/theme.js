// 테마 (spec/features/F5-page-view.md 4절 P1): auto(시스템 설정) → light → dark 순환, localStorage 기억
import { reactive } from 'vue'
import { load, save } from './enhance/storage.js'

const KEY = 'pb.theme'
const MODES = ['auto', 'light', 'dark']
export const THEME_ICON = { auto: '◐', light: '☀', dark: '☾' }
export const THEME_LABEL = { auto: '시스템 설정', light: '라이트', dark: '다크' }
const media = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null

const stored = load(KEY, 'auto')
export const theme = reactive({ mode: MODES.includes(stored) ? stored : 'auto' })

export function applyTheme() {
  const dark = theme.mode === 'dark' || (theme.mode === 'auto' && !!media?.matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
}

export function cycleTheme() {
  theme.mode = MODES[(MODES.indexOf(theme.mode) + 1) % MODES.length]
  save(KEY, theme.mode)
  applyTheme()
}

media?.addEventListener?.('change', () => {
  if (theme.mode === 'auto') applyTheme()
})
