import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3001,
    watch: {
      // 同 admin-web：忽略原子写临时目录，避免 Windows 上 EBUSY 终止 dev server
      ignored: ['**/.*.tmpdir/**', '**/.*.tmpdir', '**/*.tmp']
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 现场实况/完工防伪照片由后端 /uploads/** 静态资源映射提供
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
});
