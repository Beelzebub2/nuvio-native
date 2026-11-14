package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The player control glyphs, ported from the desktop controls sprite
 * (`player-ui/controls.html`) so phone and desktop share one icon family:
 * a 24 unit grid, 1.8 unit round strokes, and filled play and pause.
 */
internal object PlayerControlIcons {
    val Play: ImageVector by lazy {
        icon("PlayerPlay") {
            fill("M8 5.6v12.8a1 1 0 0 0 1.53.85l10.2-6.4a1 1 0 0 0 0-1.7L9.53 4.75A1 1 0 0 0 8 5.6z")
        }
    }

    val Pause: ImageVector by lazy {
        icon("PlayerPause") {
            fill(rect(6f, 4.5f, 4f, 15f, 1.3f))
            fill(rect(14f, 4.5f, 4f, 15f, 1.3f))
        }
    }

    val Replay: ImageVector by lazy {
        icon("PlayerReplay") {
            stroke("M4.5 12a7.5 7.5 0 1 0 2.2-5.3")
            stroke("M6.7 3v3.7h3.7")
        }
    }

    val Forward: ImageVector by lazy {
        icon("PlayerForward") {
            stroke("M19.5 12a7.5 7.5 0 1 1-2.2-5.3")
            stroke("M17.3 3v3.7h-3.7")
        }
    }

    val SkipNext: ImageVector by lazy {
        icon("PlayerSkipNext") {
            stroke("M5.5 6.1v11.8a.8.8 0 0 0 1.24.67l8.7-5.9a.8.8 0 0 0 0-1.33l-8.7-5.9a.8.8 0 0 0-1.24.66z")
            stroke("M18.5 5.5v13")
        }
    }

    val Back: ImageVector by lazy {
        icon("PlayerBack", autoMirror = true) { stroke("M15 5l-7 7 7 7", width = 2f) }
    }

    val ChevronRight: ImageVector by lazy {
        icon("PlayerChevronRight", autoMirror = true) { stroke("M9 5l7 7-7 7", width = 2f) }
    }

    val Flag: ImageVector by lazy {
        icon("PlayerFlag") {
            stroke("M5.5 20.5V4.5")
            stroke("M5.5 4.5h11.2a.6.6 0 0 1 .5.9L15 9l2.2 3.6a.6.6 0 0 1-.5.9H5.5")
        }
    }

    val Diagnostics: ImageVector by lazy {
        icon("PlayerDiagnostics") { stroke("M3 12.5h3.6l2.2-5.5 4.4 11 2.4-5.5H21") }
    }

    val Lock: ImageVector by lazy {
        icon("PlayerLock") {
            stroke(rect(5f, 10.5f, 14f, 10f, 2.5f))
            stroke("M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5M12 14.5v2")
        }
    }

    val LockOpen: ImageVector by lazy {
        icon("PlayerLockOpen") {
            stroke(rect(5f, 10.5f, 14f, 10f, 2.5f))
            stroke("M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2M12 14.5v2")
        }
    }

    val Subtitles: ImageVector by lazy {
        icon("PlayerSubtitles") {
            stroke(rect(3f, 5f, 18f, 14f, 3f))
            stroke("M7 12.5h3.5M13 12.5h4M7 15.5h6M15.5 15.5h1.5")
        }
    }

    val SubtitlesOff: ImageVector by lazy {
        icon("PlayerSubtitlesOff") {
            stroke("M7.5 5H18a3 3 0 0 1 3 3v8.5M18 19H6a3 3 0 0 1-3-3V8a3 3 0 0 1 1.5-2.6")
            stroke("M7 15.5h6M3.5 3.5l17 17")
        }
    }

    val Audio: ImageVector by lazy {
        icon("PlayerAudio") { stroke("M4 10v4M8 7v10M12 4.5v15M16 8v8M20 10.5v3") }
    }

    val Speed: ImageVector by lazy {
        icon("PlayerSpeed") {
            stroke("M5.07 17A8 8 0 1 1 18.93 17")
            stroke("M12 13l3.6-3.9")
            fill(circle(12f, 13f, 1.3f))
        }
    }

    val Aspect: ImageVector by lazy {
        icon("PlayerAspect") {
            stroke(rect(3f, 6f, 18f, 12f, 2.5f))
            stroke("M7.5 12h9M9.7 9.8L7.5 12l2.2 2.2M14.3 9.8l2.2 2.2-2.2 2.2")
        }
    }

    val Source: ImageVector by lazy {
        icon("PlayerSource") {
            stroke("M12 3.8l8.2 4.3L12 12.4 3.8 8.1z")
            stroke("M3.8 12.1l8.2 4.3 8.2-4.3M3.8 16l8.2 4.3 8.2-4.3")
        }
    }

    val Episodes: ImageVector by lazy {
        icon("PlayerEpisodes") {
            stroke("M4 6.5h12M4 11.5h12M4 16.5h6.5")
            stroke("M15 14.4v5.2a.5.5 0 0 0 .76.43l4.2-2.6a.5.5 0 0 0 0-.86l-4.2-2.6a.5.5 0 0 0-.76.43z")
        }
    }

    val AutoSkipIntro: ImageVector by lazy {
        icon("PlayerAutoSkipIntro") { stroke("M4.5 6.6v10.8l6.8-5.4zM12 6.6v10.8l6.8-5.4zM20.5 6v12") }
    }

    val AutoNextEpisode: ImageVector by lazy {
        icon("PlayerAutoNextEpisode") {
            stroke("M19.8 12a7.8 7.8 0 1 1-2.28-5.52")
            stroke("M17.5 2.8v3.7h-3.7")
            stroke("M10.3 9.2v5.6l4.6-2.8z")
        }
    }

    val Options: ImageVector by lazy {
        icon("PlayerOptions") {
            stroke(rect(3f, 4.5f, 18f, 6.5f, 3.25f))
            stroke(rect(3f, 13f, 18f, 6.5f, 3.25f))
            stroke(circle(6.8f, 16.25f, 1.6f))
            fill(circle(17.2f, 7.75f, 1.6f))
        }
    }

    val More: ImageVector by lazy {
        icon("PlayerMore") {
            fill(circle(5.5f, 12f, 1.7f))
            fill(circle(12f, 12f, 1.7f))
            fill(circle(18.5f, 12f, 1.7f))
        }
    }

    val Build: ImageVector by lazy {
        icon("PlayerBuild") {
            stroke("M4 7h9M17 7h3M4 17h3M11 17h9")
            stroke(circle(15f, 7f, 2f))
            stroke(circle(9f, 17f, 2f))
        }
    }

    val OpenExternal: ImageVector by lazy {
        icon("PlayerOpenExternal") {
            stroke("M14 4h6v6M20 4l-9 9M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10")
        }
    }

    // Phone-only actions drawn in the same stroke family.
    val Cast: ImageVector by lazy {
        icon("PlayerCast") {
            stroke("M3 9V7.5A2.5 2.5 0 0 1 5.5 5h13A2.5 2.5 0 0 1 21 7.5v9a2.5 2.5 0 0 1-2.5 2.5H13")
            stroke("M3 12.5a6.5 6.5 0 0 1 6.5 6.5M3 16a3 3 0 0 1 3 3")
            fill(circle(3.6f, 18.9f, 1.1f))
        }
    }

    val Handoff: ImageVector by lazy {
        icon("PlayerHandoff") {
            stroke(rect(3f, 4.5f, 18f, 12f, 2.5f))
            stroke("M9 20h6M12 16.5V20")
            stroke("M10.6 8.3v4.4l3.7-2.2z")
        }
    }
}

private class PlayerIconScope(val builder: ImageVector.Builder) {
    fun stroke(path: String, width: Float = 1.8f) {
        builder.addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun fill(path: String) {
        builder.addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
    }
}

private inline fun icon(
    name: String,
    autoMirror: Boolean = false,
    paths: PlayerIconScope.() -> Unit,
): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )
    PlayerIconScope(builder).paths()
    return builder.build()
}

/** SVG `<circle>` as path data. */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/** SVG `<rect rx>` as path data. */
private fun rect(x: Float, y: Float, width: Float, height: Float, radius: Float): String {
    val innerWidth = width - 2 * radius
    val innerHeight = height - 2 * radius
    return "M${x + radius} ${y}h$innerWidth" +
        "a$radius $radius 0 0 1 $radius ${radius}v$innerHeight" +
        "a$radius $radius 0 0 1 ${-radius} ${radius}h${-innerWidth}" +
        "a$radius $radius 0 0 1 ${-radius} ${-radius}v${-innerHeight}" +
        "a$radius $radius 0 0 1 $radius ${-radius}z"
}
