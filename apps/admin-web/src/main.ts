import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
// 必须排在 EP 样式之后：设计令牌层（视觉语言方向 D），覆盖 EP 主题变量并声明中性/状态色相
import './styles/tokens.css';
// 必须排在 EP 与 tokens 之后：窄档（<768px）全局适配层，收敛 EP 默认度量导致的横向溢出
import './styles/responsive.css';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';

import App from './App.vue';
import router from './router';

const app = createApp(App);

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}

// 单树响应式重构：仅 Element Plus 全量注册（Vant 已随双树架构移除；按需引入仍是后续优化项）
app.use(createPinia());
app.use(router);
app.use(ElementPlus);

app.mount('#app');
