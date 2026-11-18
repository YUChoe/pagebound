<script setup>
// 동시 편집 충돌 (spec/features/F1-editor.md 3절): 서버 최신본과 내 내용 비교 후 선택
import { computed } from 'vue'
import { diffLines } from '../../editor/diff.js'

const props = defineProps({
  server: { type: String, required: true },
  mine: { type: String, required: true },
  serverVersion: { type: Number, required: true },
})
const emit = defineEmits(['overwrite', 'reload', 'close'])
const lines = computed(() => diffLines(props.server, props.mine))
</script>

<template>
  <div class="modal-backdrop">
    <div class="modal">
      <h2>다른 사용자가 먼저 저장했습니다 (서버 v{{ serverVersion }})</h2>
      <p class="muted">빨간 줄은 서버에만, 초록 줄은 내 내용에만 있습니다.</p>
      <pre v-if="lines" class="diff"><span v-for="(l, i) in lines" :key="i" :class="'d-' + l.type">{{
        (l.type === 'del' ? '- ' : l.type === 'add' ? '+ ' : '  ') + l.text }}
</span></pre>
      <div v-else class="diff-split">
        <pre>{{ server }}</pre>
        <pre>{{ mine }}</pre>
      </div>
      <div class="modal-actions">
        <button type="button" @click="emit('overwrite')">내 내용으로 덮어쓰기</button>
        <button type="button" @click="emit('reload')">서버 최신본 불러오기 (내 내용은 임시 저장본으로 보관)</button>
        <button type="button" @click="emit('close')">닫기</button>
      </div>
    </div>
  </div>
</template>
