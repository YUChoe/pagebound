<script setup>
// 플로팅 버튼 (spec/features/F5-page-view.md 2절 P1): 목차, 맨 위, 맨 아래
function toToc() {
  const toc = document.querySelector('.toc-box')
  if (toc) toc.scrollIntoView({ behavior: 'smooth', block: 'start' })
  else toTop()
}

function toTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function toBottom() {
  window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' })
}
</script>

<template>
  <div class="floating-buttons">
    <button type="button" title="목차" @click="toToc">≡</button>
    <button type="button" title="맨 위" @click="toTop">↑</button>
    <button type="button" title="맨 아래" @click="toBottom">↓</button>
  </div>
</template>
