<script setup>
// 코드블록 삽입 대화상자 (spec/features/F9-code-blocks.md 6절 P1): 언어(자동완성), title, 기본 접힘
import { ref } from 'vue'

const emit = defineEmits(['insert', 'close'])
const LANGS = ['bash', 'shell', 'sql', 'java', 'kotlin', 'javascript', 'typescript', 'json', 'yaml', 'xml', 'html', 'css',
  'python', 'go', 'c', 'cpp', 'properties', 'ini', 'dockerfile', 'nginx', 'diff', 'markdown', 'mermaid', 'plaintext']
const lang = ref('')
const title = ref('')
const collapsed = ref(false)

function submit() {
  const attrs = [lang.value.trim(), title.value.trim() ? `title="${title.value.trim().replace(/"/g, '')}"` : '',
    collapsed.value ? 'collapsed' : ''].filter(Boolean).join(' ')
  emit('insert', '```' + attrs + '\n\n```')
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <form class="modal settings-form" @submit.prevent="submit" @keydown.esc="emit('close')">
      <h3>코드블록 삽입</h3>
      <label><span>언어</span><input v-model="lang" list="cb-langs" placeholder="예: java (비우면 자동 감지)" autofocus></label>
      <datalist id="cb-langs"><option v-for="l in LANGS" :key="l" :value="l" /></datalist>
      <label><span>제목 (파일명)</span><input v-model="title" placeholder="예: UserService.java"></label>
      <label class="inline"><input v-model="collapsed" type="checkbox"> 기본 접힘</label>
      <p>
        <button type="submit" class="primary">삽입</button>
        <button type="button" @click="emit('close')">취소</button>
      </p>
    </form>
  </div>
</template>
