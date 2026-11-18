import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router.js'
import { initWrap } from './enhance/codeBlocks.js'
import 'highlight.js/styles/github.css'
import './styles/main.css'
import './styles/content.css'

initWrap()
createApp(App).use(router).mount('#app')
