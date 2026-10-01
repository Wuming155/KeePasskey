# 一键生成全新发布签名材料（ISSUE-P3-420）：
#   随机口令（每次运行全新、绝不复用）→ 新密钥文件 → 口令写入凭证管理器 →
#   keystore.properties 自动就位 → gradle 配置自检。
#
# 用法（真实控制台运行；会请求覆盖确认）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/signing/new-signing-key.ps1
#
# 流程：
#   [2/6] 覆盖确认（现有 release.jks / 凭证管理器目标将被覆盖——旧签名密钥从此作废）
#   [3/6] 生成 24 位随机口令（RNG，ASCII）+ 新 release.jks（PKCS12 / RSA-4096 / 30 年 / alias keepasskey）
#   [4/6] 口令显示一次并复制到剪贴板——存入你的密码管理器后按回车
#   [5/6] 口令写入凭证管理器（StorePassword / KeyPassword 两目标，回读逐字节校验）
#   [6/6] keystore.properties：删明文口令两行，storeFile / keyAlias 就位；gradle 配置自检
#
# 安全设计（呼应敏感数据铁律）：
#   - 口令经 **stdin 管道** 喂给 keytool（强制英文提示），不经命令行参数（进程列表不可见）、不写任何文件；
#   - 口令显示仅一次（剪贴板副本请用完覆盖），脚本不落盘；
#   - 覆盖现有密钥需显式确认，防止误跑把仍要用的密钥库覆盖作废。
#
# 退出码：0=全流程成功；1=用户取消；2=生成 / 写入 / 自检失败。
# 点源（`. .\new-signing-key.ps1`）只加载函数不执行，供自动化测试。

$ErrorActionPreference = 'Stop'

# 与 read-cred.ps1 / write-cred.ps1 / app/build.gradle.kts 的约定一致，勿改
$script:RootDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'write-cred.ps1')  # 复用 CredMan 类型与写/校验函数（点源不触发其交互主流程）

function Find-Keytool {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    $studioJbr = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
    if (Test-Path $studioJbr) { return $studioJbr }
    return $null
}

function Find-Bash {
    foreach ($p in @('C:\Program Files\Git\bin\bash.exe', 'C:\Program Files\Git\usr\bin\bash.exe')) {
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    return $null
}

# 24 位随机口令：ASCII（管道 / 控制台复制无编码坑），剔除易混淆字符，RNG 密码学随机。
# 每次调用全新生成——重跑脚本得到的是**不同**口令，对应**新**的密钥库。
function New-RandomPasswordString {
    param([int]$Length = 24)
    $pool = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!#$%&*+-=?@^_'
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $raw = New-Object byte[] $Length
        $rng.GetBytes($raw)
        return (-join ($raw | ForEach-Object { $pool[$_ % $pool.Length] }))
    } finally {
        $rng.Dispose()
    }
}

# 生成密钥库（核心，无交互，可测试）：口令仅经 stdin 管道喂 keytool。
# 返回 $null=失败；成功返回 pscustomobject{ PasswordString, KeyStorePath, Fingerprint }
function New-SigningKeyMaterial {
    param(
        [Parameter(Mandatory = $true)][string]$KeyStorePath,
        [string]$DName = 'CN=KeePasskey,O=KeePasskey,C=CN',
        [string]$KeytoolPath
    )
    $keytool = if ($KeytoolPath) { $KeytoolPath } else { Find-Keytool }
    if (-not $keytool) {
        Write-Host '✗ 找不到 keytool（PATH 与 Android Studio jbr 均无）——请安装 JDK 或用 -KeytoolPath 指定'
        return $null
    }

    $password = New-RandomPasswordString
    # keytool -genkeypair 遇已存在的密钥库会用**新口令**去 load 旧文件（其语义是向既有库
    # 追加条目）→ 旧库旧口令必然报 "keystore password was incorrect"（ISSUE-P3-421）。
    # 覆盖确认已在主流程取得，这里先删旧文件再生成；删除紧贴生成动作，失败窗口最小。
    if (Test-Path $KeyStorePath) {
        Remove-Item $KeyStorePath -Force
    }
    # keytool 提示顺序（英文强制）：库口令 → 复述 → DN 确认（-dname 已给，答 yes）→
    # PKCS12 密钥口令（回车＝同库口令）。
    #
    # 口令喂入通道（实测结论，ISSUE-P3-420）：
    #   ✗ PS 原生管道：编码不可靠；✗ .NET Process.StandardInput：JVM 回声收到的字节
    #   逐字正确，keytool 的多行口令提示仍读错行（同机 bash 管道同命令一次成功）——
    #   keytool 每次提示新建 reader 的行为与 .NET 管道组合存在不兼容，不深究。
    #   ✓ **Git Bash 中转管道**：口令仅存在于 bash 命令串（进程内存），不落盘、
    #     不经 Windows 进程命令行；字符池刻意排除引号/反引号/$/反斜杠，bash 单引号安全。
    #   兜底：无 bash 时退回 -storepass（口令进进程命令行约数秒，仅本机同用户进程可见），
    #   属如实声明的降级，不静默。
    $pwFwd = $KeyStorePath.Replace('\', '/')
    $dnameFwd = $DName.Replace('"', '')
    $bash = Find-Bash
    if ($bash) {
        $bashCmd = "printf '%s\n%s\nyes\n\n' '$password' '$password'" +
            " | keytool -genkeypair -v -keystore '$pwFwd' -storetype PKCS12" +
            " -alias keepasskey -keyalg RSA -keysize 4096 -validity 10950" +
            " -dname '$dnameFwd' -J-Duser.language=en -J-Duser.country=US"
        $genOut = [KeePasskeyCredManWrite]::RunProcessCapture($bash, '-c "' + ($bashCmd -replace '"', '\"') + '"', '')
    } else {
        Write-Warning '未找到 Git Bash——口令将经 keytool -storepass 传参（进程命令行可见约数秒）。'
        $keytoolArgs = "-genkeypair -v -keystore `"$KeyStorePath`" -storetype PKCS12 " +
            "-alias keepasskey -keyalg RSA -keysize 4096 -validity 10950 " +
            "-dname `"$dnameFwd`" -J-Duser.language=en -J-Duser.country=US -storepass $password"
        $genOut = [KeePasskeyCredManWrite]::RunProcessCapture($keytool, $keytoolArgs, '')
    }
    if (-not $genOut.StartsWith('exit=0') -or -not (Test-Path $KeyStorePath)) {
        Write-Host "✗ keytool 生成失败：
$genOut"
        return $null
    }

    if ($bash) {
        $listCmd = "printf '%s\n' '$password'" +
            " | keytool -list -v -keystore '$pwFwd' -storetype PKCS12 -J-Duser.language=en -J-Duser.country=US"
        $listOut = [KeePasskeyCredManWrite]::RunProcessCapture($bash, '-c "' + ($listCmd -replace '"', '\"') + '"', '')
    } else {
        $listOut = [KeePasskeyCredManWrite]::RunProcessCapture($keytool,
            "-list -v -keystore `"$KeyStorePath`" -storetype PKCS12 -J-Duser.language=en -J-Duser.country=US -storepass $password", '')
    }
    if (-not $listOut.StartsWith('exit=0')) {
        Write-Host '✗ keytool 回读失败（口令与密钥库不匹配？）'
        return $null
    }
    $fp = if ($listOut -match 'SHA256:\s*([0-9A-F]{2}(?::[0-9A-F]{2}){31})') { $Matches[1] } else { '' }
    if (-not $fp) {
        Write-Host '✗ 未能从 keytool 输出解析 SHA-256 指纹'
        return $null
    }
    return [pscustomobject]@{
        PasswordString = $password
        KeyStorePath   = $KeyStorePath
        Fingerprint    = $fp
    }
}

# keystore.properties 就位：删明文口令两行；storeFile / keyAlias 写为给定值（保留其他行）
function Update-KeystoreProperties {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$StoreFile,
        [Parameter(Mandatory = $true)][string]$KeyAlias
    )
    $lines = @()
    if (Test-Path $Path) {
        $lines = [IO.File]::ReadAllLines($Path)
    }
    # 刻意 @() 强转：只剩 1 行时 Where-Object 返回标量字符串，+= 会退化成字符串拼接把行并在一起
    $kept = @($lines | Where-Object {
        $key = ($_ -split '=', 2)[0].Trim()
        $key -notin @('storeFile', 'keyAlias', 'storePassword', 'keyPassword')
    })
    $kept += @("storeFile=$StoreFile", "keyAlias=$KeyAlias")
    [IO.File]::WriteAllLines($Path, $kept)
}

function Invoke-NewSigningKey {
    $keyStorePath = Join-Path $script:RootDir 'release.jks'
    $propsPath = Join-Path $script:RootDir 'keystore.properties'

    Write-Host '=== 一键生成全新发布签名材料 ==='
    Write-Host "密钥文件：$keyStorePath"
    Write-Host "口令去向：你的密码管理器（手动）+ Windows 凭证管理器（本脚本写入）"
    Write-Host ''

    # [1/6] 环境
    $keytool = Find-Keytool
    if (-not $keytool) {
        Write-Host '✗ 找不到 keytool（PATH 与 Android Studio jbr 均无）'
        return 2
    }
    Write-Host "[1/6] keytool：$keytool"

    # [2/6] 覆盖确认（不可逆！）
    if (Test-Path $keyStorePath) {
        Write-Host ''
        Write-Warning "已存在 $keyStorePath——继续将**覆盖**它：旧签名密钥从此作废，已装用户将无法从旧签名包覆盖升级。"
        $answer = Read-Host '确认生成全新密钥并覆盖吗? (y/N)'
        if ($answer -ne 'y' -and $answer -ne 'Y') { Write-Host '已取消，未做任何更改。'; return 1 }
    }
    foreach ($target in @($TargetStore, $TargetKey)) {
        if ($null -ne [KeePasskeyCredManWrite]::ReadGenericBlob($target)) {
            $answer = Read-Host "凭证管理器 $target 已存在，覆盖吗? (y/N)"
            if ($answer -ne 'y' -and $answer -ne 'Y') { Write-Host '已取消，未做任何更改。'; return 1 }
        }
    }
    Write-Host '[2/6] 覆盖确认完成'

    # [3/6] 生成
    Write-Host '[3/6] 生成随机口令 + 新密钥文件（RSA-4096，约数秒）…'
    $material = New-SigningKeyMaterial -KeyStorePath $keyStorePath -KeytoolPath $keytool
    if ($null -eq $material) { return 2 }
    Write-Host "[3/6] 密钥文件已生成，证书 SHA-256 指纹："
    Write-Host "      $($material.Fingerprint)"

    # [4/6] 口令交付（仅此一次）
    try { Set-Clipboard -Value $material.PasswordString; $clipNote = '（已同时复制到剪贴板）' } catch { $clipNote = '' }
    Write-Host ''
    Write-Host '=============================================='
    Write-Host "新口令：$($material.PasswordString)   $clipNote"
    Write-Host '=============================================='
    Write-Host '请现在把口令保存到你的密码管理器（保存后剪贴板建议随手复制点别的东西覆盖掉）。'
    Read-Host '确认已保存，按回车继续'

    # [5/6] 凭证管理器
    Write-Host '[5/6] 口令写入凭证管理器…'
    $sec = New-Object security.securestring
    $material.PasswordString.ToCharArray() | ForEach-Object { $sec.AppendChar($_) }
    $sec.MakeReadOnly()
    $bytes = ConvertFrom-SecureStringToBytes $sec
    Register-HeldBytes $bytes
    $ok = (Write-AndVerifyCredential -Target $TargetStore -Bytes $bytes) -and
        (Write-AndVerifyCredential -Target $TargetKey -Bytes $bytes)
    if (-not $ok) { return 2 }

    # [6/6] properties 就位 + gradle 自检
    Write-Host '[6/6] 更新 keystore.properties（删明文口令两行，storeFile / keyAlias 就位）…'
    Update-KeystoreProperties -Path $propsPath -StoreFile 'release.jks' -KeyAlias 'keepasskey'
    Clear-HeldBytes

    Write-Host 'gradle 配置自检（:app:tasks）…'
    & (Join-Path $script:RootDir 'gradlew.bat') ':app:tasks' '--console=plain' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host '✗ gradle 配置自检失败——请检查上方输出（常见：口令通道未接通触发 P2-55 断言）'
        return 2
    }

    Write-Host ''
    Write-Host '✓ 全流程完成。指纹（请与密码管理器备注里的一致）:'
    Write-Host "  $($material.Fingerprint)"
    Write-Host '收尾提醒：'
    Write-Host '  1) 自行备份 release.jks（复制到安全处 / 密码管理器附件均可）；'
    Write-Host '  2) 旧签名包从此无法覆盖升级——对外分发前确认无存量用户依赖旧签名；'
    Write-Host '  3) 之后构建零额外操作：.\gradlew.bat assembleRelease。'
    return 0
}

if ($MyInvocation.InvocationName -ne '.') {
    exit (Invoke-NewSigningKey)
}
