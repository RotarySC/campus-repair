import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
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
