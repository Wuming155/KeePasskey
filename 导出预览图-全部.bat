@echo off
setlocal
chcp 65001 >nul
title KeePasskey — 导出全部 @Preview 截图

cd /d "%~dp0"

echo ============================================
echo  导出全部 Compose @Preview 截图
echo  输出: preview-exports\all\  （按包路径分层）
echo ============================================
echo.

echo [1/3] 生成 screenshotTest wrapper...
where python >nul 2>nul
if %errorlevel%==0 (
  python tools\export_previews\generate_screenshot_test_wrappers.py
) else (
  where py >nul 2>nul
  if %errorlevel%==0 (
    py -3 tools\export_previews\generate_screenshot_test_wrappers.py
  ) else (
    echo [警告] 未找到 python，跳过 wrapper 重新生成
  )
)
if errorlevel 1 (
  echo [失败] wrapper 生成出错
  pause
  exit /b 1
)

echo.
echo [2/3] 渲染并导出全部预览图...
call gradlew.bat :app:exportAllPreviewScreenshots --console=plain
if errorlevel 1 (
  echo.
  echo [失败] 导出未完成，请查看上方 Gradle 日志
  pause
  exit /b 1
)

echo.
echo [3/3] 完成
if exist "preview-exports\all" (
  start "" explorer "%CD%\preview-exports\all"
) else (
  echo 未找到 preview-exports\all
)

echo.
pause
