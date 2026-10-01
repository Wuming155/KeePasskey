# 从 Windows 凭证管理器读取「普通凭据」（generic credential）的口令字段（ISSUE-P3-418）。
#
# 用途：app/build.gradle.kts 的发布签名口令解析链第三通道——
#       环境变量 → Windows 凭证管理器 → keystore.properties。
#       口令存凭证管理器（DPAPI 按当前用户加密），不再明文落盘。
#
# 用法：
#   powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/signing/read-cred.ps1 -Name <目标名>
#   例：-Name "KeePasskey/Keystore/StorePassword"
#
# 写入方式（二选一，UI 方式可避免 shell 对特殊字符的转义问题）：
#   ① 控制面板 → 用户账户 → 凭据管理器 → Windows 凭据 → 添加普通凭据：
#      Internet 或网络地址 = KeePasskey/Keystore/StorePassword，用户名 = keepasskey，密码 = 口令
#   ② 命令行：cmdkey /generic:"KeePasskey/Keystore/StorePassword" /user:keepasskey /pass:"<口令>"
#
# 退出码约定（调用方 app/build.gradle.kts 依赖，勿改动语义）：
#   0 = 读取成功，口令已写 stdout（UTF-8，无尾随换行）
#   1 = 目标不存在 / 无口令 blob（属预期：未配置该通道，调用方静默降级）
#   2 = 脚本或 API 调用异常（诊断写 stderr，调用方告警后降级）
#
# 注意：本脚本 stdout 只承载口令本身，禁止加任何日志输出到 stdout。

param(
    [Parameter(Mandatory = $true)]
    [string]$Name
)

$ErrorActionPreference = 'Stop'

# 统一输出编码：口令按 UTF-8 出 stdout（调用方按 UTF-8 解码）
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

$source = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class KeePasskeyCredMan {
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
    private static extern bool CredReadW(string target, int type, int flags, out IntPtr credentialPtr);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    // type 1 = CRED_TYPE_GENERIC（「普通凭据」）；域凭据是 type 2，刻意不读
    public static string ReadGenericPassword(string target) {
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
            // cmdkey / 凭据管理器 UI 写入的口令 blob 为 UTF-16LE
            return Encoding.Unicode.GetString(blob).TrimEnd('\0');
        } finally {
            CredFree(credentialPtr);
        }
    }
}
'@

try {
    Add-Type -TypeDefinition $source
} catch {
    # 类型已加载（同进程重复调用）或其他加载失败——后者会在下方调用处再次暴露
}

try {
    $password = [KeePasskeyCredMan]::ReadGenericPassword($Name)
} catch {
    [Console]::Error.WriteLine("read-cred: CredRead failed for '$Name': $($_.Exception.Message)")
    exit 2
}

if ([string]::IsNullOrEmpty($password)) {
    exit 1
}

[Console]::Out.Write($password)
exit 0
