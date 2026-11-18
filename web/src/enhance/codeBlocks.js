// 코드블록 보기/접기 (spec/features/F9-code-blocks.md P0)
import hljs from 'highlight.js/lib/common'
import { copyText, hash, load, save } from './storage.js'

const CLAMP_LINES = 25
const NO_HIGHLIGHT_LINES = 2000
const FOLD_INDEX = 'pb.fold.index'
const FOLD_MAX = 1000
const WRAP_KEY = 'pb.codeWrap'
// 언어 미지정 블록 자동 감지 후보와 임계값 (M2 실데이터 697블록으로 조정)
// yaml·ini 등은 셸 출력·주석 블록 오탐이 많아 제외. 12 기준 bash 63, sql 26, json 6, java 3, xml 1, 나머지 plaintext
const AUTO_LANGS = ['bash', 'sql', 'java', 'xml', 'json']
export const AUTO_RELEVANCE = 12

// 기존 문서에 쓰인 properties 언어는 common 번들에 없으므로 ini로 처리
hljs.registerAliases(['properties'], { languageName: 'ini' })

function foldKey(pageId, blockKey) {
  return `pb.fold.${pageId}.${blockKey}`
}

function rememberFold(pageId, blockKey, collapsed) {
  const key = foldKey(pageId, blockKey)
  const index = load(FOLD_INDEX, []).filter((k) => k !== key)
  index.push(key)
  while (index.length > FOLD_MAX) {
    try {
      window.localStorage.removeItem(index.shift())
    } catch {
      break
    }
  }
  save(key, collapsed)
  save(FOLD_INDEX, index)
}

function button(label, title, onClick) {
  const b = document.createElement('button')
  b.type = 'button'
  b.className = 'cb-btn'
  b.textContent = label
  b.title = title
  b.addEventListener('click', onClick)
  return b
}

export function setWrap(on) {
  save(WRAP_KEY, on)
  document.documentElement.classList.toggle('code-wrap', on)
}

export function initWrap() {
  document.documentElement.classList.toggle('code-wrap', load(WRAP_KEY, false) === true)
}

function highlight(block, codeEl, lang) {
  const text = codeEl.textContent
  if (Number(block.dataset.lines) > NO_HIGHLIGHT_LINES) return
  if (lang && hljs.getLanguage(lang)) {
    codeEl.innerHTML = hljs.highlight(text, { language: lang, ignoreIllegals: true }).value
    return
  }
  if (lang) return
  const auto = hljs.highlightAuto(text, AUTO_LANGS)
  if (auto.relevance >= AUTO_RELEVANCE) {
    codeEl.innerHTML = auto.value
    const label = block.querySelector('.cb-lang')
    if (label) {
      label.textContent = auto.language
      label.classList.add('detected')
    }
  }
}

function setCollapsed(block, collapsed) {
  block.classList.toggle('is-collapsed', collapsed)
  const toggle = block.querySelector('.cb-toggle')
  if (toggle) toggle.textContent = collapsed ? '▸' : '▾'
}

// container 안의 .codeblock 에 헤더·접기·높이 제한·줄 번호·복사를 붙인다. 반환: { setAll(collapsed) }
export function enhanceCodeBlocks(container, pageId) {
  const blocks = [...container.querySelectorAll('.codeblock')]
  const titleCount = new Map()
  const observer = 'IntersectionObserver' in window
    ? new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        if (!e.isIntersecting) return
        observer.unobserve(e.target)
        highlight(e.target, e.target.querySelector('code'), e.target.dataset.lang)
      })
    }, { rootMargin: '200px' })
    : null

  const items = blocks.map((block) => {
    const codeEl = block.querySelector('code')
    const text = codeEl.textContent
    const { lang, title } = block.dataset
    const lines = Number(block.dataset.lines)

    let blockKey
    if (title) {
      const n = (titleCount.get(title) ?? 0) + 1
      titleCount.set(title, n)
      blockKey = n > 1 ? `${title}#${n}` : title
    } else {
      blockKey = hash(lang + '\n' + text)
    }

    const header = document.createElement('div')
    header.className = 'cb-header'
    const toggle = button('▾', '접기/펼치기', () => {
      const collapsed = !block.classList.contains('is-collapsed')
      setCollapsed(block, collapsed)
      rememberFold(pageId, blockKey, collapsed)
    })
    toggle.classList.add('cb-toggle')
    const meta = document.createElement('span')
    meta.className = 'cb-meta'
    meta.innerHTML = '<span class="cb-lang"></span><span class="cb-title"></span><span class="cb-lines"></span>'
    meta.querySelector('.cb-lang').textContent = lang
    meta.querySelector('.cb-title').textContent = title
    meta.querySelector('.cb-lines').textContent = `${lines}줄`
    const preview = document.createElement('span')
    preview.className = 'cb-preview'
    preview.textContent = text.split('\n')[0]
    const actions = document.createElement('span')
    actions.className = 'cb-actions'
    actions.append(
      button('줄바꿈', '줄바꿈 켜기/끄기', () => setWrap(!document.documentElement.classList.contains('code-wrap'))),
      button('복사', '코드 복사', async (e) => {
        await copyText(text)
        e.target.textContent = '복사됨'
        setTimeout(() => { e.target.textContent = '복사' }, 1500)
      }),
    )
    header.append(toggle, meta, preview, actions)
    block.prepend(header)

    if (block.dataset.linenums === '1') {
      const start = Number(block.dataset.start) || 1
      const gutter = document.createElement('span')
      gutter.className = 'cb-gutter'
      gutter.setAttribute('aria-hidden', 'true')
      gutter.textContent = Array.from({ length: lines }, (_, i) => start + i).join('\n')
      block.querySelector('pre').prepend(gutter)
    }

    if (lines > CLAMP_LINES) {
      block.classList.add('is-clamped')
      const more = button(`${lines - CLAMP_LINES}줄 더 보기`, '', () => {
        const clamped = block.classList.toggle('is-clamped')
        more.textContent = clamped ? `${lines - CLAMP_LINES}줄 더 보기` : '줄이기'
      })
      more.classList.add('cb-more')
      block.append(more)
    }

    const stored = load(foldKey(pageId, blockKey))
    setCollapsed(block, stored ?? block.dataset.collapsed === '1')

    if (observer) observer.observe(block)
    else highlight(block, codeEl, lang)
    return { block, blockKey }
  })

  return {
    count: items.length,
    setAll(collapsed) {
      items.forEach(({ block, blockKey }) => {
        setCollapsed(block, collapsed)
        rememberFold(pageId, blockKey, collapsed)
      })
    },
    disconnect() {
      observer?.disconnect()
    },
  }
}
