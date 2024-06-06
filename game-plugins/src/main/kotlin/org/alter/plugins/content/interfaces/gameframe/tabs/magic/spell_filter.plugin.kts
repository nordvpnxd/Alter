package org.alter.plugins.content.interfaces.spellfilter

import org.alter.game.model.interf.DisplayMode
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.DISABLE_FILTERS_VARBIT
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.FILTER_BY_LEVEL_VARBIT
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.FILTER_BY_RUNES_VARBIT
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.FILTER_COMBAT_VARBIT
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.FILTER_TELEPORTS_VARBIT
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.FILTER_UTILITY_VARBIT
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.SPELL_FILTER_COMPONENT_ID
import org.alter.plugins.content.interfaces.spellfilter.SpellFilters.SPELL_FILTER_INTERFACE_ID

on_login {
    player.setInterfaceEvents(interfaceId = SPELL_FILTER_INTERFACE_ID, component = SPELL_FILTER_COMPONENT_ID, range = 0..4, setting = 2)
}

DisplayMode.values.forEach { mode ->
    val child =
        when (mode) {
            DisplayMode.RESIZABLE_NORMAL -> 77
            DisplayMode.RESIZABLE_LIST -> 77
            DisplayMode.FIXED -> 75
            else -> return@forEach
        }
    on_button(interfaceId = getDisplayComponentId(mode), component = child) {
        val opt = player.getInteractingOption()
        if (opt == 2) {
            player.toggleVarbit(DISABLE_FILTERS_VARBIT)
        }
    }
}

on_button(interfaceId = SPELL_FILTER_INTERFACE_ID, component = SPELL_FILTER_COMPONENT_ID) {
    val varbit =
        when (player.getInteractingSlot()) {
            0 -> FILTER_COMBAT_VARBIT
            1 -> FILTER_TELEPORTS_VARBIT
            2 -> FILTER_UTILITY_VARBIT
            3 -> FILTER_BY_LEVEL_VARBIT
            4 -> FILTER_BY_RUNES_VARBIT
            else -> return@on_button
        }
    player.toggleVarbit(varbit)
}
