package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxEntry
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.UUID

/** Opaque, process-local handles for individual history snapshot objects (ISSUE-P2-632). */
internal object EntryRevisionHandle {

    class LocatedRevision(
        val index: Int,
        val snapshot: KdbxEntry
    )

    private class SnapshotReference(
        snapshot: KdbxEntry,
        queue: ReferenceQueue<KdbxEntry>?
    ) : WeakReference<KdbxEntry>(snapshot, queue) {
        private val identityHash = System.identityHashCode(snapshot)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SnapshotReference) return false
            val snapshot = get() ?: return false
            return snapshot === other.get()
        }
    }

    private val lock = Any()
    private val collectedSnapshots = ReferenceQueue<KdbxEntry>()
    private val handleBySnapshot = HashMap<SnapshotReference, String>()
    private val snapshotByHandle = HashMap<String, SnapshotReference>()

    /** Reuses a token while this exact snapshot object remains live in the database history. */
    fun encode(index: Int, snapshot: KdbxEntry): String {
        require(index >= 0)
        return synchronized(lock) {
            removeCollectedSnapshots()
            val lookup = SnapshotReference(snapshot, null)
            handleBySnapshot[lookup] ?: UUID.randomUUID().toString().also { handle ->
                val reference = SnapshotReference(snapshot, collectedSnapshots)
                handleBySnapshot[reference] = handle
                snapshotByHandle[handle] = reference
            }
        }
    }

    /** Resolves only the exact object represented by the handle; stale/foreign handles fail closed. */
    fun resolve(history: List<KdbxEntry>, handle: String): LocatedRevision? = synchronized(lock) {
        removeCollectedSnapshots()
        val reference = snapshotByHandle[handle] ?: return@synchronized null
        val snapshot = reference.get() ?: run {
            removeSnapshot(reference)
            return@synchronized null
        }
        val index = history.indexOfFirst { it === snapshot }
        if (index < 0) null else LocatedRevision(index, snapshot)
    }

    private fun removeCollectedSnapshots() {
        while (true) {
            val reference = collectedSnapshots.poll() as? SnapshotReference ?: return
            removeSnapshot(reference)
        }
    }

    private fun removeSnapshot(reference: SnapshotReference) {
        snapshotByHandle.remove(handleBySnapshot.remove(reference))
    }
}
