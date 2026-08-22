package dev.supermessage.kit

/**
 * Whether a timeline change should animate.
 *
 * A port of `TimelineCollectionView.swift:426`, which is a *decision* and so
 * does not belong in a composable — this app's central rule is that the view
 * decides nothing. It lives here rather than in the core only because that
 * would cost a Rust change and a binding rebuild for a rule no other platform
 * is asking to share yet; moving it later is a rename.
 */
object TimelineAnimation {
    /**
     * @param arrived how many rows appeared at the newest end
     * @param had how many rows were there before
     * @param hasApplied whether any snapshot has been applied to this room yet
     * @param wasAway whether the reader was scrolled away from the newest end
     */
    fun animates(arrived: Int, had: Int, hasApplied: Boolean, wasAway: Boolean): Boolean {
        // A room's first fill is the room appearing, not an arrival; a reader
        // who was away did not watch it happen; and an empty room gaining rows
        // is a fill. Any of the three and nothing animates.
        if (!hasApplied || wasAway || had <= 0) return false
        // More than a handful at once is a page of history or a resync.
        return arrived in 1..3
    }
}
