package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconForkLeft
import com.vayunmathur.library.ui.IconForkRight
import com.vayunmathur.library.ui.IconMerge
import com.vayunmathur.library.ui.IconRampLeft
import com.vayunmathur.library.ui.IconRampRight
import com.vayunmathur.library.ui.IconRoundaboutLeft
import com.vayunmathur.library.ui.IconRoundaboutRight
import com.vayunmathur.library.ui.IconStraight
import com.vayunmathur.library.ui.IconTurnLeft
import com.vayunmathur.library.ui.IconTurnRight
import com.vayunmathur.library.ui.IconTurnSharpLeft
import com.vayunmathur.library.ui.IconTurnSharpRight
import com.vayunmathur.library.ui.IconTurnSlightLeft
import com.vayunmathur.library.ui.IconTurnSlightRight
import com.vayunmathur.library.ui.IconUTurn
import com.vayunmathur.library.ui.IconUTurnRight
import com.vayunmathur.maps.R
import com.vayunmathur.maps.util.RouteService.API.Maneuver

/** Composable icon for a maneuver, or null when the maneuver has no icon. */
fun Maneuver.iconContent(): (@Composable (Modifier, Color) -> Unit)? = when (this) {
    Maneuver.TURN_SLIGHT_LEFT -> { m, t -> IconTurnSlightLeft(m, t) }
    Maneuver.TURN_SHARP_LEFT -> { m, t -> IconTurnSharpLeft(m, t) }
    Maneuver.UTURN_LEFT -> { m, t -> IconUTurn(m, t) }
    Maneuver.TURN_LEFT -> { m, t -> IconTurnLeft(m, t) }
    Maneuver.TURN_SLIGHT_RIGHT -> { m, t -> IconTurnSlightRight(m, t) }
    Maneuver.TURN_SHARP_RIGHT -> { m, t -> IconTurnSharpRight(m, t) }
    Maneuver.UTURN_RIGHT -> { m, t -> IconUTurnRight(m, t) }
    Maneuver.TURN_RIGHT -> { m, t -> IconTurnRight(m, t) }
    Maneuver.STRAIGHT -> { m, t -> IconStraight(m, t) }
    Maneuver.RAMP_LEFT -> { m, t -> IconRampLeft(m, t) }
    Maneuver.RAMP_RIGHT -> { m, t -> IconRampRight(m, t) }
    Maneuver.MERGE -> { m, t -> IconMerge(m, t) }
    Maneuver.FORK_LEFT -> { m, t -> IconForkLeft(m, t) }
    Maneuver.FORK_RIGHT -> { m, t -> IconForkRight(m, t) }
    Maneuver.ROUNDABOUT_LEFT -> { m, t -> IconRoundaboutLeft(m, t) }
    Maneuver.ROUNDABOUT_RIGHT -> { m, t -> IconRoundaboutRight(m, t) }
    Maneuver.DEPART -> { m, t -> IconStraight(m, t) }
    Maneuver.NAME_CHANGE -> { m, t -> IconStraight(m, t) }
    Maneuver.WAIT -> { m, t ->
        Icon(painterResource(R.drawable.outline_nest_clock_farsight_analog_24), null, m, t)
    }
    Maneuver.RIDE -> { m, t ->
        Icon(painterResource(R.drawable.outline_menu_book_24), null, m, t)
    }
    Maneuver.FERRY, Maneuver.FERRY_TRAIN, Maneuver.MANEUVER_UNSPECIFIED -> null
}
