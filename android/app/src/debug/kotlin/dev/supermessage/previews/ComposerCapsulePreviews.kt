package dev.supermessage.previews

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.ChipState
import dev.supermessage.Composer
import dev.supermessage.LocalThumbnailDispatcher
import dev.supermessage.kit.stores.EditTarget
import dev.supermessage.kit.stores.ReplyTarget
import dev.supermessage.kit.stores.StagedAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import uniffi.supermessage_ffi.StagedFile

/** The capsule at rest: the field, "+" and a send control with nothing to send. */
@Preview(name = "Composer capsule, empty", showBackground = true, heightDp = 120)
@Composable
internal fun ComposerCapsuleEmpty() {
    PreviewGround { Composer(text = "", onTextChange = {}, onSend = {}) }
}

/** Reply strip, a picture chip and a message in one capsule. */
@Preview(name = "Composer capsule, replying with a chip", showBackground = true, heightDp = 260)
@Composable
internal fun ComposerReplyingWithChip() {
    PreviewGround {
        Composer(
            text = "This one?",
            onTextChange = {},
            onSend = {},
            replyTo = ReplyTarget.Pending(
                eventId = "\$m1",
                sender = "Rakesh",
                excerpt = "Which screenshot has the new header?",
            ),
            attachment = ChipState(
                file = StagedFile("t1", "header-after.pdf", 2_411_724uL, "application/pdf", null, null),
                path = null,
                failure = null,
            ),
        )
    }
}

/** A picture shows itself: a decoded thumbnail, not an icon (C3). */
@Preview(name = "Composer capsule, image chip", showBackground = true, heightDp = 190)
@Composable
internal fun ComposerImageChip() {
    val context = LocalContext.current
    // Drawn here rather than shipped as a resource: a fixed pattern, so the
    // frame is the same every render.
    val path = remember {
        val file = File(context.cacheDir, "preview-thumbnail.png")
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (x in 0 until 96 step 16) {
            paint.color = if ((x / 16) % 2 == 0) 0xFF2356C2.toInt() else 0xFFF28B82.toInt()
            canvas.drawRect(x.toFloat(), 0f, x + 16f, 64f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }
    CompositionLocalProvider(LocalThumbnailDispatcher provides Dispatchers.Unconfined) {
        PreviewGround {
            Composer(
                text = "",
                onTextChange = {},
                onSend = {},
                attachment = ChipState(
                    file = StagedFile("t2", "IMG_20261022_0817.png", 184_320uL, "image/png", 96uL, 64uL),
                    path = path,
                    failure = null,
                ),
            )
        }
    }
}

/**
 * A file that could not go, with its reason on the chip in `danger` and the
 * text held back; the failure line under the capsule is empty, as on iOS.
 */
@Preview(name = "Composer capsule, chip failed", showBackground = true, heightDp = 190)
@Composable
internal fun ComposerChipFailed() {
    PreviewGround {
        Composer(
            text = "Here is the recording.",
            onTextChange = {},
            onSend = {},
            attachment = ChipState(
                file = null,
                path = null,
                failure = StagedAttachment.Failure(
                    "standup-recording-2026-10-22.mov",
                    "That file is 48 MB; the limit is 10 MB.",
                ),
            ),
        )
    }
}

/** Editing keeps "+" and shows a tick, labelled "Save edit". */
@Preview(name = "Composer capsule, editing", showBackground = true, heightDp = 200)
@Composable
internal fun ComposerEditing() {
    PreviewGround {
        Composer(
            text = "Rebased onto main and the token diff is empty now.",
            onTextChange = {},
            onSend = {},
            editing = EditTarget.Pending(
                eventId = "\$m1",
                body = "Rebased onto main and the token diff is empty now.",
            ),
        )
    }
}

/** The `@` picker above the capsule: an agent (labelled, with its host) first, then a person (C4). */
@Preview(name = "MentionPicker", showBackground = true, heightDp = 260)
@Composable
internal fun MentionPicker() {
    PreviewGround {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            modifier = androidx.compose.ui.Modifier.padding(10.dp),
        ) {
            dev.supermessage.MentionPicker(
                people = listOf(
                    uniffi.supermessage_core.PersonDto(
                        "@atlas:x", "Atlas", "A", uniffi.supermessage_core.RuntimeDto("claude", "ci"), null,
                    ),
                    uniffi.supermessage_core.PersonDto("@priya:x", "Priya", "P", null, null),
                ),
                onPick = {},
            )
            Composer(text = "@a", onTextChange = {}, onSend = {})
        }
    }
}
