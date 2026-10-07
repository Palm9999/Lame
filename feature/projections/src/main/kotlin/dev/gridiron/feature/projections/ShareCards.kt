package dev.gridiron.feature.projections

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import dev.gridiron.core.data.live.Grade
import dev.gridiron.core.data.live.ReportCard
import dev.gridiron.core.data.live.WeekRecap
import dev.gridiron.core.data.live.WeekReview
import dev.gridiron.core.projections.TradeOutcome
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun CardLine(text: String, strong: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
}

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

/** The league's latest week, with the user's own result first when there is one. */
@Composable
public fun WeeklyRecapShareCard(recap: WeekRecap, mine: WeekReview?) {
    CardTitle("Week ${recap.week} around the league")
    mine?.takeIf { it.week == recap.week }?.let { CardLine("You scored ${one(it.scored)} of a possible ${one(it.best)}", strong = true) }
    recap.highScore?.let { (team, score) -> CardLine("Top score: $team, ${one(score)}") }
    recap.blowout?.let { g -> CardLine("Biggest win: ${g.winner} over ${g.loser} by ${one(g.margin)}") }
    recap.closest?.let { g -> CardLine("Closest: ${g.winner} over ${g.loser} by ${one(g.margin)}") }
    if (recap.topScorers.isNotEmpty()) {
        CardLine("Best starters", strong = true)
        for ((p, team) in recap.topScorers) CardLine("${p.name} ${one(p.espnPoints ?: 0.0)} ($team)")
    }
}

/** Every manager's overall place and category places, the user's marked. */
@Composable
public fun ReportShareCard(cards: List<ReportCard>, myTeamId: Int?) {
    CardTitle("Report card")
    for (c in cards) {
        val mine = c.teamId == myTeamId
        CardLine("${placeText(c.overall)} · ${c.name}" + if (mine) " (you)" else "", strong = mine)
        Text(
            Grade.entries.mapNotNull { g -> c.places[g]?.let { "${g.label} ${placeText(it)}" } }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A player's week: his projection and likely range, TD chance and rest of season. */
@Composable
public fun PlayerShareCard(name: String, subtitle: String, card: ProjectionCard) {
    CardTitle(name)
    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val week = listOfNotNull("Week ${card.week}", card.matchup).joinToString(" · ")
    when {
        card.bye -> CardLine("$week: bye", strong = true)
        card.notThisWeek -> CardLine("$week: not projected", strong = true)
        card.out -> CardLine("$week: out", strong = true)
        else -> {
            CardLine("$week: ${one(card.points)} pts", strong = true)
            CardLine(listOfNotNull("Likely ${one(card.floor)}–${one(card.ceiling)}", card.tdChance?.let { "TD ${(it * 100).roundToInt()}%" }).joinToString(" · "))
        }
    }
    card.rosPoints?.let { ros -> CardLine("Rest of season ${one(ros)} pts" + (card.rosPerGame?.let { " (${one(it)} per game)" } ?: "")) }
}

/** A trade with [partner]: who goes where, the verdict and what it does to both best lineups. */
@Composable
public fun TradeShareCard(partner: String, give: List<String>, get: List<String>, outcome: TradeOutcome) {
    CardTitle(verdict(outcome))
    CardLine("You send: ${give.joinToString().ifEmpty { "nobody" }}")
    CardLine("You get: ${get.joinToString().ifEmpty { "nobody" }}")
    CardLine("You ${gainText(outcome.myGain)} · $partner ${gainText(outcome.theirGain)} rest of season", strong = true)
}
