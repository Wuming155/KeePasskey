@echo off
setlocal
title KeePasskey - Export Main Preview Screenshots
cd /d "%~dp0"

echo ============================================
echo  Export MAIN Compose @Preview screenshots
echo  Output: preview-exports\main\{light,dark}
echo ============================================
echo.

echo [1/3] Generate screenshotTest wrappers (locale zh-CN)...
where python >nul 2>nul
if %errorlevel%==0 (
  python tools\export_previews\generate_screenshot_test_wrappers.py
) else (
  where py >nul 2>nul
  if %errorlevel%==0 (
    py -3 tools\export_previews\generate_screenshot_test_wrappers.py
  ) else (
    echo [WARN] python not found, skip wrapper regen
  )
)
if errorlevel 1 (
  echo [FAIL] wrapper generation failed
  pause
  exit /b 1
)

echo.
echo [2/3] Render and export main previews...
call gradlew.bat :app:exportMainPreviewScreenshots --console=plain
if errorlevel 1 (
  echo.
  echo [FAIL] export failed, see Gradle log above
  pause
  exit /b 1
)

echo.
echo [3/3] Done
if exist "preview-exports\main" (
  start "" explorer "%CD%\preview-exports\main"
) else (
  echo preview-exports\main not found
)
echo.
pause