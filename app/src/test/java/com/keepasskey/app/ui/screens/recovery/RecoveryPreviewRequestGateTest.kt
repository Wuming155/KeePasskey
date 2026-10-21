package com.keepasskey.app.ui.screens.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPreviewRequestGateTest {
    private val gate = RecoveryPreviewRequestGate()

    @Test
    fun `只有当前代次和副本载荷可以发布预览`() {
        val targetA = identity(path = "/vault/a.kdbx", digest = "a")
        val targetB = identity(path = "/vault/b.kdbx", digest = "b")

        val requestA = gate.begin(targetA)
        val requestB = gate.begin(targetB)

        assertFalse(gate.matches(requestA, targetA))
        assertFalse(gate.matches(requestB, targetA))
        assertTrue(gate.matches(requestB, targetB))
    }

    @Test
    fun `选择或刷新失效后迟到预览不能重新授权恢复`() {
        val target = identity(path = "/vault/a.kdbx", digest = "original")
        val request = gate.begin(target)

        gate.invalidate()

        assertFalse(gate.matches(request, target))
        assertFalse(gate.matches(gate.currentGeneration, target))
    }

    @Test
    fun `同一库路径下替换载荷摘要也不能复用旧预览`() {
        val original = identity(path = "/vault/a.kdbx", digest = "original")
        val replaced = identity(path = "/vault/a.kdbx", digest = "replacement")
        val request = gate.begin(original)

        assertFalse(gate.matches(request, replaced))
        assertTrue(gate.matches(request, original))
    }

    private fun identity(path: String, digest: String) = RecoveryViewModel.TargetIdentity(
        vaultId = "vault-id",
        pathIdentifier = path,
        payloadFileName = "payload.kre",
        payloadLength = 100,
        payloadSha256 = digest
    )
}
