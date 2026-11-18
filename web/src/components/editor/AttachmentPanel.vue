<script setup>
// 에디터 첨부 패널 (spec/features/F2-attachments.md 3절)
import { ref } from 'vue'

const props = defineProps({
  items: { type: Array, required: true }, // [{ id, filename, mime, size, url }]
  content: { type: String, default: '' }, // 현재 편집 중인 본문 (미참조 판정)
  hasPage: { type: Boolean, default: false },
})
const emit = defineEmits(['upload', 'insert', 'rename', 'remove'])
const picker = ref(null)

function pick(e) {
  const files = [...(e.target.files ?? [])]
  if (files.length) emit('upload', files)
  e.target.value = ''
}

function rename(item) {
  const name = window.prompt('새 파일명', item.filename)
  if (name && name.trim() && name.trim() !== item.filename) emit('rename', item, name.trim())
}

function remove(item) {
  if (window.confirm(`첨부 "${item.filename}"을(를) 삭제할까요? 본문의 링크는 깨집니다.`)) emit('remove', item)
}
</script>

<template>
  <aside class="attach-panel">
    <div class="attach-head">
      <strong>첨부 파일</strong>
      <button type="button" @click="picker.click()">업로드</button>
      <input ref="picker" type="file" multiple hidden @change="pick" />
    </div>
    <p v-if="!hasPage && !items.length" class="muted">붙여넣기·드래그·업로드로 첨부하면 초안이 먼저 저장됩니다.</p>
    <ul>
      <li v-for="a in items" :key="a.id" class="attach-item">
        <img v-if="a.mime.startsWith('image/')" :src="a.url" alt="" class="thumb" loading="lazy" />
        <div class="attach-info">
          <a :href="a.url" target="_blank" rel="noopener" :title="a.filename">{{ a.filename }}</a>
          <span class="muted">{{ (a.size / 1024).toFixed(1) }} KB</span>
          <span v-if="!props.content.includes(a.url)" class="badge warn">본문 미참조</span>
        </div>
        <div class="attach-actions">
          <button type="button" @click="emit('insert', a)">삽입</button>
          <button type="button" @click="rename(a)">이름</button>
          <button type="button" @click="remove(a)">삭제</button>
        </div>
      </li>
    </ul>
  </aside>
</template>
