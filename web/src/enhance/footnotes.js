// 각주 툴팁 (spec/features/F5-page-view.md 2절 P1): 본문 [1]에 마우스를 올리면 하단 각주 내용 표시
export function enhanceFootnotes(container) {
  container.querySelectorAll('sup.footnote-ref a[href^="#fn"]').forEach((a) => {
    const target = container.querySelector(a.getAttribute('href'))
    if (!target) return
    const clone = target.cloneNode(true)
    clone.querySelectorAll('.footnote-backref').forEach((b) => b.remove())
    a.title = clone.textContent.trim()
    a.addEventListener('mouseenter', () => {
      const tip = document.createElement('div')
      tip.className = 'fn-tooltip'
      tip.append(...clone.cloneNode(true).childNodes)
      const r = a.getBoundingClientRect()
      tip.style.left = `${Math.max(8, r.left + window.scrollX - 20)}px`
      tip.style.top = `${r.bottom + window.scrollY + 4}px`
      document.body.append(tip)
      a.title = ''
      a.addEventListener('mouseleave', () => tip.remove(), { once: true })
    })
  })
}
