# DolphinScheduler UI 本地启动与打包速记

## 环境
- Node.js 16+（当前可用 22.x）
- pnpm 7+（当前 10.x 也可用）
- 当前版本: node: v22.21.1, npm: 10.9.4,pnpm: 10.15.0

## 配置
- `.env.development`：`VITE_APP_DEV_WEB_URL=http://127.0.0.1:12345`
- `.env.production`：`VITE_APP_PROD_WEB_URL=http://127.0.0.1:12345`

## 开发启动
```bash
cd dolphinscheduler-ui
pnpm install          # 首次安装依赖
pnpm run dev          # 默认 http://localhost:5173
```
Vite 代理会把 `/dolphinscheduler/*` 转发到 `VITE_APP_DEV_WEB_URL`。

## 生产构建
```bash
pnpm run build:prod   # 输出到 dist/
```
若使用 TSX 需保证 `tsconfig.json` 已包含：
```json
"types": ["vite/client", "vue/jsx"]
```

## 生产预览（本地看打包结果）
```bash
pnpm run preview -- --host --port 4173
# 访问 http://localhost:4173
```

## 将前端挂到后端路径（无 Nginx）
1) 构建产物：`pnpm run build:prod`
2) 拷贝 dist 到后端静态目录（示例）：
   ```
   mkdir -p ../dolphinscheduler-api/src/main/resources/static/ui
   cp -r dist/* ../dolphinscheduler-api/src/main/resources/static/ui/
   ```
3) 若需通过 `/dolphinscheduler/ui/` 访问，可在后端 `application.yaml` 将
   `spring.mvc.static-path-pattern` 调整为 `/**`，重启后访问
   `http://127.0.0.1:12345/dolphinscheduler/ui/`。

