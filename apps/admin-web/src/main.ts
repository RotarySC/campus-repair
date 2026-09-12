import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import Vant from 'vant';
import 'vant/lib/index.css';

import App from './App.vue';
import router from './router';
import { getDeviceMode } from './utils/device';

const app = createApp(App);

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}

// 统一门户：桌面树用 Element Plus，移动树用 Vant（D5 v1 全量注册，路由懒加载兜底）
app.use(createPinia());
app.use(router);
app.use(ElementPlus);
app.use(Vant);

document.body.classList.add(getDeviceMode() === 'mobile' ? 'device-mobile' : 'device-desktop');

app.mount('#app');
