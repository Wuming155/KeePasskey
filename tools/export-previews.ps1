# 导出全部 Compose @Preview 为 PNG（走 AGENTS.md 规定的 Android CLI 入口）。
#
# 前提：Android Studio 已启动并打开了本项目（否则 `android studio check` 会报
#       "No running Studio instances found"）。这是 Android CLI 的硬性要求。
#
# 用法（在仓库根目录执行）：
#   pwsh -File tools/export-previews.ps1
#   pwsh -File tools/export-previews.ps1 -Filter VaultList     # 只导文件名含 VaultList 的
#
# 产物：build/preview-export/<相对路径转下划线>__<预览函数名>.png
# 说明：build/ 已在 .gitignore 内，产物不会入库。
#
# 已知限界（如实声明，勿当成缺陷）：
#   `render-compose-preview` 只接受「文件 + composable 名」，**没有**指定 @Preview 变体
#   （浅色 / 深色）的参数，因此单次调用通常只产出该预览函数的默认变体。哪个变体是默认、
#   以及输出文件名是否会带变体后缀，取决于 Studio 端实现；脚本按「一次调用一个文件」写，
#   并把实际产出的文件名原样打印出来，不做任何猜测。若你需要浅/深两套都落盘，可先让
#   Android Studio 的预览面板渲染一次（它会缓存两套），或改用官方截图测试（见
#   docs 中的方案对比）——那条路能按变体逐个出图。

[CmdletBinding()]
param(
    [string]$Filter = '',
    [int]$PerPreviewTimeoutSec = 180
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$cli = Join-Path $env:USERPROFILE '.android\bin\android-cli.exe'
$outDir = Join-Path $repoRoot 'build\preview-export'

if (-not (Test-Path $cli)) {
    throw "未找到 Android CLI：$cli（见 AGENTS.md「Android CLI 调试约定」）"
}

# 前置检查：必须有 Studio 实例在跑，否则逐个调用只会全部失败
$studioCheck = & $cli studio check 2>&1 |
    Where-Object { $_ -notmatch 'Picked up JAVA_TOOL_OPTIONS|Failed to upload metrics' }
if ($studioCheck -match 'No running Studio instances') {
    throw "没有运行中的 Android Studio 实例。请先打开 Android Studio 并打开本项目（$repoRoot），再重跑本脚本。"
}
Write-Host "Studio 实例：" -NoNewline
$studioCheck | Select-Object -First 3 | ForEach-Object { Write-Host $_ }

if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

# 盘点 @Preview：每文件取第一条 @Preview 所归属的预览函数
$scanRoot = Join-Path $repoRoot 'app\src\main\java'
$targets = @()
foreach ($f in (Get-ChildItem -Path $scanRoot -Recurse -Filter *.kt)) {
    $text = Get-Content $f.FullName -Raw
    if ($text -notmatch '@(androidx\.compose\.ui\.tooling\.preview\.)?Preview\(') { continue }
    if ($Filter -and ($f.Name -notlike "*$Filter*")) { continue }

    $lines = Get-Content $f.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '@(androidx\.compose\.ui\.tooling\.preview\.)?Preview\(') {
            for ($j = $i; $j -lt [Math]::Min($i + 12, $lines.Count); $j++) {
                if ($lines[$j] -match '^\s*(?:private|internal|public)?\s*fun\s+(\w+)\s*\(') {
                    $targets += [pscustomobject]@{
                        File       = $f.FullName
                        RelPath    = $f.FullName.Substring($repoRoot.Length + 1).Replace('\', '/')
                        Composable = $Matches[1]
                        Line       = $j + 1
                    }
                    break
                }
            }
            break
        }
    }
}

if ($targets.Count -eq 0) { throw "没有匹配到任何 @Preview 目标（Filter='$Filter'）" }

Write-Host "`n待导出：$($targets.Count) 个界面" -ForegroundColor Cyan
Write-Host "输出目录：$outDir`n" -ForegroundColor Cyan

$before = @(Get-ChildItem $outDir -Filter *.png -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty Name)
$ok = 0
$failed = @()

foreach ($t in $targets) {
    $stem = ($t.RelPath -replace '^app/src/main/java/', '') -replace '[\\/]', '_' -replace '\.kt$', ''
    $outFile = Join-Path $outDir "$stem`__$($t.Composable).png"
    Write-Host ("→ {0,-45} {1}:{2}" -f $t.Composable, $t.RelPath, $t.Line) -NoNewline
    try {
        $out = & $cli studio render-compose-preview $t.File $t.Composable --output-image-file $outFile 2>&1 |
            Where-Object { $_ -notmatch 'Picked up JAVA_TOOL_OPTIONS|Failed to upload metrics' }
        if (Test-Path $outFile) {
            Write-Host '  ✓' -ForegroundColor Green
            $ok++
        } else {
            Write-Host '  ✗ 未产出图片' -ForegroundColor Yellow
            $failed += "$($t.Composable) :: $($out -join ' / ')"
        }
    } catch {
        Write-Host "  ✗ $($_.Exception.Message)" -ForegroundColor Red
        $failed += "$($t.Composable) :: $($_.Exception.Message)"
    }
}

# 如实打印本次真正新增的文件名（不猜测命名规则）
$after = @(Get-ChildItem $outDir -Filter *.png -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty Name)
$new = $after | Where-Object { $_ -notin $before }

Write-Host "`n完成：成功 $ok / $($targets.Count)。" -ForegroundColor Cyan
Write-Host "本次新增 PNG：$($new.Count) 个" -ForegroundColor Cyan
$new | Select-Object -First 10 | ForEach-Object { Write-Host "  - $_" }
if ($new.Count -gt 10) { Write-Host "  …（其余 $($new.Count - 10) 个见输出目录）" }
if ($failed.Count -gt 0) {
    Write-Host "`n失败清单（$($failed.Count)）：" -ForegroundColor Yellow
    $failed | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
}
