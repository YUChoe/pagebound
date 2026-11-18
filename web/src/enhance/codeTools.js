// 코드블록 헤더 도구 (spec/features/F9-code-blocks.md 3.1절 P1): 전체화면 ⤢, ⋮ 메뉴(원문 보기, 다운로드, 기본 접힘 저장)
const EXT = { javascript: 'js', typescript: 'ts', python: 'py', bash: 'sh', shell: 'sh', kotlin: 'kt', markdown: 'md', yaml: 'yml' }

function modal(content) {
  const backdrop = document.createElement('div')
  backdrop.className = 'cb-modal'
  const panel = document.createElement('div')
  panel.className = 'cb-modal-panel'
  const close = document.createElement('button')
  close.type = 'button'
  close.className = 'cb-modal-close'
  close.textContent = '닫기 (Esc)'
  panel.append(close, content)
  backdrop.append(panel)
  const onKey = (e) => { if (e.key === 'Escape') remove() }
  function remove() {
    backdrop.remove()
    document.removeEventListener('keydown', onKey)
  }
  close.addEventListener('click', remove)
  backdrop.addEventListener('click', (e) => { if (e.target === backdrop) remove() })
  document.addEventListener('keydown', onKey)
  document.body.append(backdrop)
  return remove
}

export function fullscreen(block) {
  const pre = block.querySelector('pre').cloneNode(true)
  pre.removeAttribute('hidden')
  pre.classList.add('cb-full')
  const wrap = document.createElement('div')
  wrap.className = 'codeblock is-full'
  wrap.append(pre)
  modal(wrap)
}

export function showRaw(text) {
  const pre = document.createElement('pre')
  pre.className = 'cb-raw'
  pre.textContent = text
  modal(pre)
}

export function download(text, title, lang) {
  const name = title || `snippet.${EXT[lang] ?? (lang || 'txt')}`
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = name.split('/').pop()
  document.body.append(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

// ⋮ 메뉴. onDefaultCollapsed가 있으면(편집 권한자) 기본 접힘 저장/해제 항목 표시
export function menuButton(makeButton, block, text, { onDefaultCollapsed } = {}) {
  const wrap = document.createElement('span')
  wrap.className = 'cb-menu-wrap'
  const list = document.createElement('div')
  list.className = 'cb-menu'
  list.hidden = true
  const item = (label, action) => {
    const b = makeButton(label, '', () => {
      list.hidden = true
      action()
    })
    list.append(b)
  }
  item('원문 보기', () => showRaw(text))
  item('파일로 다운로드', () => download(text, block.dataset.title, block.dataset.lang))
  if (onDefaultCollapsed) {
    const collapsed = block.dataset.collapsed === '1'
    item(collapsed ? '기본 접힘 해제' : '기본 접힘으로 저장', () => onDefaultCollapsed(Number(block.dataset.index), !collapsed))
  }
  const toggle = makeButton('⋮', '더 보기', (e) => {
    e.stopPropagation()
    list.hidden = !list.hidden
  })
  document.addEventListener('click', (e) => { if (!wrap.contains(e.target)) list.hidden = true })
  wrap.append(toggle, list)
  return wrap
}
