# 交互式把发布签名口令写入 Windows 凭证管理器「普通凭据」（ISSUE-P3-419）。
#
# 与 tools/signing/read-cred.ps1 配套：写入后 app/build.gradle.kts 的口令解析链
# （环境变量 → Windows 凭证管理器 → keystore.properties）即自动改走凭证管理器通道。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/signing/write-cred.ps1
#   （交互式：控制台掩码输入，不回显；Ctrl+C 可随时中止）
#
# 设计（呼应敏感数据铁律）：
#   - 输入走 Read-Host -AsSecureString：口令不经命令行参数（避免进入 shell 历史 /
#     进程列表），也不写任何文件；注意 Read-Host 不吃重定向 stdin，本脚本只能交互运行；
#   - 口令全程驻留 SecureString / 非托管内存，仅在 CredWrite 所需的 UTF-16LE 字节
#     缓冲中短暂存在，写入并回读校验后立即清零（Clear-HeldBytes）；
#   - 回读校验在 C# 内逐字节比对，明文不回到 PowerShell 字符串层。
#
# 结构约定：点源（`. .\write-cred.ps1`）只加载类型与函数、不进交互流程——
# 供自动化测试与将来复用（`Invoke-WriteSigningCredentials` 之外的核心函数可单测）。
#
# 退出码：0=成功写入并校验；1=用户取消；2=API / 写入 / 校验失败。
# 注意：凭证目标与 read-cred.ps1 约定一致，勿改（app/build.gradle.kts 依赖）：
#   KeePasskey/Keystore/StorePassword、KeePasskey/Keystore/KeyPassword

$ErrorActionPreference = 'Stop'

$TargetStore = 'KeePasskey/Keystore/StorePassword'
$TargetKey = 'KeePasskey/Keystore/KeyPassword'
$MinPasswordLength = 16  # 与 app/build.gradle.kts 的 P2-55 构建期断言同源

$source = @'
using System;
using System.Linq;
using System.Runtime.InteropServices;

public static class KeePasskeyCredManWrite {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CREDENTIAL {
        public int Flags;
        public int Type;
        public string TargetName;
        public string Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public int CredentialBlobSize;
        public IntPtr CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias;
        public string UserName;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredWriteW(ref CREDENTIAL credential, int flags);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredReadW(string target, int type, int flags, out IntPtr credentialPtr);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    // type 1 = CRED_TYPE_GENERIC（「普通凭据」）；Persist 2 = CRED_PERSIST_LOCAL_MACHINE
    public static bool WriteGenericPassword(string target, byte[] blob, string userName, out int win32Error) {
        IntPtr blobPtr = Marshal.AllocHGlobal(blob.Length);
        try {
            Marshal.Copy(blob, 0, blobPtr, blob.Length);
            CREDENTIAL credential = new CREDENTIAL {
                Flags = 0,
                Type = 1,
                TargetName = target,
                Comment = "KeePasskey release signing password (ISSUE-P3-418/419)",
                CredentialBlobSize = blob.Length,
                CredentialBlob = blobPtr,
                Persist = 2,
                AttributeCount = 0,
                Attributes = IntPtr.Zero,
                TargetAlias = null,
                UserName = userName
            };
            if (!CredWriteW(ref credential, 0)) {
                win32Error = Marshal.GetLastWin32Error();
                return false;
            }
            win32Error = 0;
            return true;
        } finally {
            Marshal.FreeHGlobal(blobPtr);
        }
    }

    // 回读 blob（逐字节校验用；调用方负责清零返回的托管数组）
    public static byte[] ReadGenericBlob(string target) {
        IntPtr credentialPtr;
        if (!CredReadW(target, 1, 0, out credentialPtr)) {
            return null;
        }
        try {
            CREDENTIAL credential = (CREDENTIAL)Marshal.PtrToStructure(credentialPtr, typeof(CREDENTIAL));
            if (credential.CredentialBlob == IntPtr.Zero || credential.CredentialBlobSize <= 0) {
                return null;
            }
            byte[] blob = new byte[credential.CredentialBlobSize];
            Marshal.Copy(credential.CredentialBlob, blob, 0, blob.Length);
            return blob;
        } finally {
            CredFree(credentialPtr);
        }
    }

    public static bool BlobEquals(byte[] a, byte[] b) {
        return a != null && b != null && a.Length == b.Length && a.SequenceEqual(b);
    }
}
'@

try {
    Add-Type -TypeDefinition $source
} catch { }

function ConvertFrom-SecureStringToBytes {
    param([security.securestring]$Secure)
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($Secure)
    try {
        $bytes = New-Object byte[] ($Secure.Length * 2)
        [Runtime.InteropServices.Marshal]::Copy($ptr, $bytes, 0, $bytes.Length)
        return ,$bytes
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($ptr)
    }
}

# 所有经手过的明文字节缓冲统一登记在此，交互流程结束（成功/取消/异常）时清零
$script:heldBytes = @()

function Register-HeldBytes {
    param([byte[]]$Bytes)
    $script:heldBytes += ,$Bytes
}

function Clear-HeldBytes {
    foreach ($b in $script:heldBytes) {
        if ($null -ne $b) { [Array]::Clear($b, 0, $b.Length) }
    }
}

function Read-PasswordBytes {
    param([string]$Prompt)
    Write-Host -NoNewline "$Prompt"
    $sec = Read-Host -AsSecureString
    $bytes = ConvertFrom-SecureStringToBytes $sec
    Register-HeldBytes $bytes
    return ,$bytes
}

# keyPassword 留空 ＝ 与 storePassword 同值（密钥库与密钥口令通常一致）
function Resolve-KeyPasswordBytes {
    param([byte[]]$StoreBytes, [byte[]]$KeyBytes)
    if ($null -eq $KeyBytes -or $KeyBytes.Length -eq 0) {
        $copy = New-Object byte[] $StoreBytes.Length
        [Array]::Copy($StoreBytes, $copy, $StoreBytes.Length)
        Register-HeldBytes $copy
        return ,$copy
    }
    return ,$KeyBytes
}

# 写入单个目标并回读逐字节校验；返回 $true=成功
function Write-AndVerifyCredential {
    param([string]$Target, [byte[]]$Bytes)
    $err = 0
    if (-not [KeePasskeyCredManWrite]::WriteGenericPassword($Target, $Bytes, 'keepasskey', [ref]$err)) {
        Write-Host "✗ 写入 $Target 失败（Win32 错误码 $err）"
        return $false
    }
    $readBack = [KeePasskeyCredManWrite]::ReadGenericBlob($Target)
    $ok = [KeePasskeyCredManWrite]::BlobEquals($Bytes, $readBack)
    if ($null -ne $readBack) { [Array]::Clear($readBack, 0, $readBack.Length) }
    if ($ok) {
        Write-Host "✓ 已写入并回读校验一致：$Target"
    } else {
        Write-Host "✗ 写入 $Target 后回读不一致——请重跑本脚本或改用凭据管理器 UI 写入"
    }
    return $ok
}

function Invoke-WriteSigningCredentials {
    Write-Host '把发布签名口令写入 Windows 凭证管理器（普通凭据，DPAPI 按当前用户加密）'
    Write-Host "目标：$TargetStore"
    Write-Host "      $TargetKey"
    Write-Host '输入不回显；Ctrl+C 可随时中止。'
    Write-Host ''

    try {
        $storeBytes = Read-PasswordBytes 'storePassword（密钥库口令）: '
        if ($storeBytes.Length -eq 0) {
            Write-Host '空口令——已取消，未写入任何凭据。'
            return 1
        }

        $keyInput = Read-PasswordBytes 'keyPassword（密钥口令，直接回车＝与 storePassword 相同）: '
        $keyBytes = Resolve-KeyPasswordBytes -StoreBytes $storeBytes -KeyBytes $keyInput

        $storeChars = $storeBytes.Length / 2
        if ($storeChars -lt $MinPasswordLength) {
            Write-Host ''
            Write-Warning "口令长度 $storeChars < $MinPasswordLength——app/build.gradle.kts 的 P2-55 构建期断言会 fail-closed 拒绝出包（需先 re-key）。"
            $answer = Read-Host '仍要写入吗? (y/N)'
            if ($answer -ne 'y' -and $answer -ne 'Y') {
                Write-Host '已取消，未写入任何凭据。'
                return 1
            }
        }

        # 目标已存在时先确认覆盖（CredWrite 是无条件覆盖）
        foreach ($target in @($TargetStore, $TargetKey)) {
            $existing = [KeePasskeyCredManWrite]::ReadGenericBlob($target)
            if ($null -ne $existing) {
                [Array]::Clear($existing, 0, $existing.Length)
                Write-Host ''
                $answer = Read-Host "凭据 $target 已存在，覆盖吗? (y/N)"
                if ($answer -ne 'y' -and $answer -ne 'Y') {
                    Write-Host '已取消，未写入任何凭据。'
                    return 1
                }
            }
        }

        Write-Host ''
        $allOk = (Write-AndVerifyCredential -Target $TargetStore -Bytes $storeBytes) -and
            (Write-AndVerifyCredential -Target $TargetKey -Bytes $keyBytes)
        if (-not $allOk) {
            return 2
        }
    } finally {
        Clear-HeldBytes
    }

    Write-Host ''
    Write-Host '后续步骤：'
    Write-Host '  1) 从本地 keystore.properties 删除 storePassword / keyPassword 两行（明文口令不再需要；storeFile / keyAlias 保留）；'
    Write-Host '  2) 跑一次 .\gradlew.bat :app:tasks 确认配置阶段无 P2-55 报错（凭证管理器通道已生效）；'
    Write-Host '  3) CI 仍走 KEYSTORE_PASSWORD / KEY_PASSWORD 环境变量，不受影响。'
    return 0
}

if ($MyInvocation.InvocationName -ne '.') {
    exit (Invoke-WriteSigningCredentials)
}
