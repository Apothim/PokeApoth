package com.pokedaisey.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pokedaisey.app.companion.HuntTracker
import com.pokedaisey.app.companion.SHINY_CUTOFF_MOD
import com.pokedaisey.app.companion.SHINY_CUTOFF_STOCK
import com.pokedaisey.app.companion.ShinyFind
import com.pokedaisey.app.companion.data.speciesName

/**
 * HUNT: the soft-reset shiny tracker ([HuntTracker]) in the OPTION look. The
 * rows show this hunt's resets, every reset ever, the shiny odds in use (tap
 * to switch between the modded 1/256 and stock 1/8192), the last wild Pokémon
 * checked with its shiny number, and the last shiny found. +1 / -1 fix a count
 * the tracker missed; NEW HUNT zeroes this hunt after a confirm.
 */
@Composable
fun HuntScreen(modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val st by HuntTracker.state.collectAsState()
    var confirmNew by remember { mutableStateOf(false) }

    val cutoff = if (st.modOdds) SHINY_CUTOFF_MOD else SHINY_CUTOFF_STOCK
    val odds = if (st.modOdds) "1/256 (MOD)" else "1/8192 (STOCK)"
    val seen = st.lastSeen?.let { "${speciesName(it.species)}  ${it.value}" } ?: "NONE YET"
    val found = st.lastShiny?.let { "${speciesName(it.species)} AT ${it.atResets}" } ?: "NONE YET"
    val rows = listOf<Triple<String, String?, () -> Unit>>(
        Triple("RESETS", st.resets.toString(), {}),
        Triple("ALL RESETS", st.totalResets.toString(), {}),
        Triple("SHINY ODDS", odds) { HuntTracker.setModOdds(!st.modOdds) },
        Triple("LAST WILD", seen, {}),
        Triple("LAST SHINY", found, {}),
    )

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OptionTitleWindow("HUNT", m, trailing = odds)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                    OptionRows(rows, m, Modifier.fillMaxWidth().weight(1f), minRow = m.rowHeight * 1.1f, labelWeight = 0.45f)
                    GbaText(
                        "Counts a reset each time the game reboots. A wild POKéMON or one in your party is shiny when its number is below $cutoff.",
                        OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3,
                        modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                    )
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), modifier = Modifier.fillMaxWidth()) {
                OptionButton("-1", m, onClick = { HuntTracker.addReset(-1) }, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                OptionButton("+1", m, onClick = { HuntTracker.addReset(1) }, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                OptionButton("NEW HUNT", m, onClick = { confirmNew = true }, emphasis = true, modifier = Modifier.weight(2f).height(m.rowHeight * 1.4f))
            }
        }
        if (confirmNew) {
            OptionConfirm(
                "NEW HUNT?", "Sets this hunt's reset count back to 0. ALL RESETS keeps counting.", "START",
                m, onConfirm = { confirmNew = false; HuntTracker.newHunt() }, onDismiss = { confirmNew = false },
            )
        }
    }
}

/** The "shiny found" strip above whatever tab is open; a tap closes it. */
@Composable
fun ShinyBanner(find: ShinyFind?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val name = find?.let { speciesName(it.species) }.orEmpty()
    val after = find?.let { " (${it.atResets} RESETS)" }.orEmpty()
    OptionTitleWindow(
        "SHINY $name!$after", m, trailing = "TAP TO CLOSE",
        modifier = modifier.soundClickable(onClick = onDismiss),
    )
}
