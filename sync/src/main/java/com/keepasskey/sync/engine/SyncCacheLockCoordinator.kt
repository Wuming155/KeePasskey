package com.keepasskey.sync.engine

import java.io.File

internal object SyncCacheLockCoordinator {

    private const val STRIPE_COUNT = 128
    private const val STRIPE_MASK = STRIPE_COUNT - 1

    private val stripes = Array(STRIPE_COUNT) { Any() }

    internal fun canonicalDir(dir: File): String {
        val path = try {
            dir.canonicalPath
        } catch (_: Exception) {
            dir.absoluteFile.normalize().path
        }
        return if (File.separatorChar == '\\') {
            path.lowercase()
        } else {
            path
        }
    }

    private fun stripeIndex(dir: File, vaultScope: String, remotePath: String): Int {
        val normalizedRemotePath = remotePath.replace('\\', '/')
        val canonical = canonicalDir(dir)
        var h = 17
        h = 31 * h + canonical.hashCode()
        h = 31 * h + vaultScope.hashCode()
        h = 31 * h + normalizedRemotePath.hashCode()
        h = h xor (h ushr 16)
        h = h * -0x7a143595
        h = h xor (h ushr 13)
        h = h * -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) and STRIPE_MASK
    }

    fun lockFor(dir: File, vaultScope: String, remotePath: String): Any {
        return stripes[stripeIndex(dir, vaultScope, remotePath)]
    }
}
