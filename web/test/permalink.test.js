import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/markdown/render.js'
import { enhanceSections, revealAnchor } from '../src/enhance/sections.js'

describe('섹션 영구 링크', () => {
  it('헤딩마다 # 복사 버튼, URL은 /p/{id}#헤딩id', () => {
    const el = document.createElement('div')
    el.innerHTML = renderMarkdown('# 개요\n본문\n## 설정 파일\n내용').html
    enhanceSections(el, { permalinkBase: 'http://wiki/p/7' })
    const urls = [...el.querySelectorAll('.sec-anchor')].map((b) => b.dataset.url)
    expect(urls).toEqual(['http://wiki/p/7#h-%EA%B0%9C%EC%9A%94', 'http://wiki/p/7#h-%EC%84%A4%EC%A0%95-%ED%8C%8C%EC%9D%BC'])
  })

  it('앵커 대상이 접힌 섹션 안이면 펼친다', () => {
    const el = document.createElement('div')
    el.innerHTML = renderMarkdown('# 개요\n## 설정\n내용').html
    enhanceSections(el)
    document.body.appendChild(el)
    const outer = el.querySelector('.sec')
    outer.querySelector('.sec-fold').click()
    expect(outer.classList.contains('folded')).toBe(true)
    Element.prototype.scrollIntoView = () => {}
    expect(revealAnchor('h-설정')).toBe(true)
    expect(outer.classList.contains('folded')).toBe(false)
    el.remove()
  })
})
