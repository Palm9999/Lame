package dev.gridiron.feature.projections

/** One waiver claim in priority order: its FAAB [bid], and [onlyIfFails] the claim above it whose drop it shares (ESPN cancels it if that one wins). */
internal data class Claim(val priority: Int, val line: PickupLine, val bid: Int, val onlyIfFails: Int?)

/** The week's claims and where the user's FAAB stands in the league ([standing], null when no rival's is known). */
internal data class ClaimPlan(val claims: List<Claim>, val standing: String?)

/** "#1 Add Pat ($50), drop Sam · only if #1 fails". */
internal fun claimText(claim: Claim): String =
    "#${claim.priority} Add ${claim.line.add.name} ($${claim.bid})" +
        (claim.line.drop?.let { ", drop ${it.name}" } ?: "") +
        (claim.onlyIfFails?.let { " · only if #$it fails" } ?: "")

/** How many claims the plan lists. */
internal const val MAX_CLAIMS = 5

/**
 * The rest-of-season [adds] as ordered claims, best gain first, with [left] dollars of FAAB. Each claim bids ([faabBid])
 * from what is left after the claims above it that could win alongside it: a claim sharing a drop with one above can
 * only go through if that one fails, so its bid doesn't count against it, and of claims sharing a drop only the largest
 * bid counts against the others. Never more than [left] in all, and no claim
 * once the money is gone. [others] are the other teams' names and FAAB left (null when unknown).
 */
internal fun claimPlan(adds: List<PickupLine>, left: Int, others: List<Pair<String, Int?>>): ClaimPlan {
    val claims = mutableListOf<Claim>()
    for (line in adds.sortedByDescending { it.gain }) {
        if (claims.size == MAX_CLAIMS) break
        val dropId = line.drop?.playerId
        val waitsOn = dropId?.let { d -> claims.firstOrNull { it.line.drop?.playerId == d }?.priority }
        // Claims sharing a drop can't both win: each such group can spend at most its largest bid.
        val committed = claims.filter { dropId == null || it.line.drop?.playerId != dropId }
            .groupBy { it.line.drop?.playerId ?: "open:${it.priority}" }.values.sumOf { group -> group.maxOf { it.bid } }
        val bid = faabBid(line.gain, left - committed)
        if (bid <= 0) continue
        claims += Claim(claims.size + 1, line, bid, waitsOn)
    }
    val known = others.mapNotNull { (name, money) -> money?.let { name to it } }
    val standing = if (known.isEmpty()) {
        null
    } else {
        val rank = 1 + known.count { it.second > left }
        val (richest, most) = known.maxBy { it.second }
        val sorted = known.map { it.second }.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        "You have $$left, ${placeText(rank)} most of ${known.size + 1}. Most: $richest $$most; median $$median."
    }
    return ClaimPlan(claims, standing)
}
