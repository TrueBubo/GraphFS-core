/** What the simulations need from a placement algorithm: [ReplicaPlacer] or [DistributedPlacer]. */
interface Placer {
    val stats: Stats
    val fileCount: Int
    fun addFile(file: Int)

    /** Adds a new file reached by a traversal from [near]; placers that do not use that just add it. */
    fun addFile(file: Int, near: Int) = addFile(file)
    fun removeFile(file: Int)

    /** Takes [file] out of the placer, but its replicas stay stored (and take disk space) until [restoreFile]. */
    fun evictFile(file: Int)

    /**
     * Re-adds a file that left the placer but is still stored on [stored] (its servers when it
     * left, see [evictFile]): it stays there at no cost; only replicas that cannot stay are copied
     * (alpha each).
     */
    fun restoreFile(file: Int, stored: IntArray)
    fun addEdge(edge: Pair<Int, Int>): Access
    fun checkInvariants()

    /** Whether [file] is in the placer. */
    fun isKnown(file: Int): Boolean

    /** The servers holding [file]'s live replicas. */
    fun replicasOf(file: Int): List<Int>
}
