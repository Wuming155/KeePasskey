@echo off
setlocal
title KeePasskey - Export Secondary Preview Screenshots
cd /d "%~dp0"

echo ============================================
echo  Export SECONDARY (non-main) @Preview shots
echo  Output: preview-exports\secondary\{light,dark}
echo ============================================
echo.

echo [1/3] Generate screenshotTest wrappers (locale zh-CN)...
rem 运行期判定（ISSUE-P3-516）：括号块是**单一解析单元**，块内 `%errorlevel%` 在进入块前
rem 就被冻结 ⇒ 旧写法的 `py` 回退分支（内层 if）在任何机器上都不可达：只有 py 启动器而 PATH
rem 无 python 的机器上，回退本应运行却没运行，脚本打印 WARN 后继续用陈旧 / 缺失的包装导出。
rem 故此处统一改用 `if errorlevel N` / `if not errorlevel N` 特殊形式（cmd 在运行期取真实码），
rem 并把「既无 python 也无 py」判为硬失败——不允许带着陈旧包装继续导出。
set "PYCMD="
where python >nul 2>nul
if not errorlevel 1 set "PYCMD=python"
if not defined PYCMD (
  where py >nul 2>nul
  if not errorlevel 1 set "PYCMD=py -3"
)
if not defined PYCMD (
  echo [FAIL] neither "python" nor "py" launcher is on PATH; cannot regenerate wrappers
  pause
  exit /b 1
)
echo   using: %PYCMD%
%PYCMD% tools\export_previews\generate_screenshot_test_wrappers.py
if errorlevel 1 (
  echo [FAIL] wrapper generation failed
  pause
  exit /b 1
)

echo.
echo [2/3] Render and export secondary previews...
call gradlew.bat :app:exportSecondaryPreviewScreenshots --console=plain
if errorlevel 1 (
  echo.
  echo [FAIL] export failed, see Gradle log above
  pause
  exit /b 1
)

echo.
echo [3/3] Done
if exist "preview-exports\secondary" (
  start "" explorer "%CD%\preview-exports\secondary"
) else (
  echo preview-exports\secondary not found
)
echo.
pause