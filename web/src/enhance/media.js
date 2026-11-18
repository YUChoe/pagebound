// mermaid 지연 렌더, 이미지 클릭 확대

let mermaidPromise = null
let seq = 0

function loadMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'default' })
      return mermaid
    })
  }
  return mermaidPromise
}

async function renderMermaid(block) {
  const src = block.querySelector('.mermaid-src')
  if (!src) return
  try {
    const mermaid = await loadMermaid()
    const { svg } = await mermaid.render(`mermaid-${++seq}`, src.textContent)
    block.innerHTML = svg
  } catch (e) {
    block.classList.add('mermaid-error')
    block.title = `mermaid 렌더 실패: ${e.message}`
  }
}

export function enhanceMermaid(container) {
  const blocks = [...container.querySelectorAll('.mermaid-block')]
  if (!blocks.length || !('IntersectionObserver' in window)) return null
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (!e.isIntersecting) return
      observer.unobserve(e.target)
      renderMermaid(e.target)
    })
  }, { rootMargin: '300px' })
  blocks.forEach((b) => observer.observe(b))
  return observer
}

export function enhanceImages(container, open) {
  container.querySelectorAll('img').forEach((img) => {
    if (img.closest('a')) return
    img.classList.add('zoomable')
    img.addEventListener('click', () => open(img.currentSrc || img.src, img.alt))
  })
}
