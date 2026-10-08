package dev.gridiron.core.data.live

import java.io.File

/**
 * Each trade's grade as it was first seen (each team's rest-of-season points in less out), kept per scoring profile in
 * [file] so later projections don't regrade it with hindsight. One line per trade: `profile|trade<TAB>team=points;…`.
 */
public class TradeGradeStore(private val file: File) {
    /** [grades] by trade id, each replaced by the one kept from its first sighting under [profileId]; new ones are kept now. */
    @Synchronized
    public fun keep(profileId: String, grades: Map<String, Map<Int, Double>>): Map<String, Map<Int, Double>> {
        val all = read()
        val fresh = grades.filterKeys { "$profileId|$it" !in all }
        if (fresh.isNotEmpty()) {
            for ((id, g) in fresh) all["$profileId|$id"] = g
            file.parentFile?.mkdirs()
            file.writeText(all.entries.joinToString("") { (k, g) -> "$k\t${g.entries.joinToString(";") { (t, v) -> "$t=$v" }}\n" })
        }
        return grades.keys.associateWith { all.getValue("$profileId|$it") }
    }

    private fun read(): MutableMap<String, Map<Int, Double>> {
        if (!file.isFile) return LinkedHashMap()
        return file.readLines().mapNotNull { line ->
            val (key, body) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            key to body.split(';').filter { it.isNotEmpty() }.mapNotNull { p ->
                val (t, v) = p.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                t.toIntOrNull()?.let { team -> v.toDoubleOrNull()?.let { team to it } }
            }.toMap()
        }.toMap(LinkedHashMap())
    }
}
