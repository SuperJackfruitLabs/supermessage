package dev.supermessage.notify

import org.json.JSONObject

/**
 * What a push names, read off the Matrix push gateway body the distributor
 * delivers unchanged: `{"notification": {"event_id", "room_id", "counts":
 * {"unread"}, "devices": [...]}}` (Matrix Push Gateway API, `/notify`).
 *
 * This is the host's own transport, as `RemotePush.swift` is iOS's. The
 * pusher is `event_id_only` (`C/push.rs`), so that is all a push carries:
 * the app fetches, decrypts and words the event itself. A UnifiedPush
 * message on this path is not encrypted by the distributor, so
 * `PushMessage.decrypted` is false and is not required.
 */
internal data class MatrixPush(
    val roomId: String,
    val eventId: String,
    /** The account's unread count as the homeserver computed it; `null` when not sent. */
    val unread: Int?,
) {
    companion object {
        /**
         * The longest room or event id the Matrix spec allows (255, sigil and
         * server name included). Longer is no id, so a job's input stays far
         * inside WorkManager's 10 KB however a body is padded.
         */
        const val MAX_ID_LENGTH = 255

        /** `null` unless the body names both a room and an event: there is nothing to fetch. */
        fun parse(bytes: ByteArray): MatrixPush? {
            val root = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return null
            }
            val notification = root.optJSONObject("notification") ?: return null
            val roomId = notification.text("room_id") ?: return null
            val eventId = notification.text("event_id") ?: return null
            return MatrixPush(roomId, eventId, notification.optJSONObject("counts")?.count("unread"))
        }

        /** A non-empty id within [MAX_ID_LENGTH]; `optString` would turn JSON `null` into `"null"`. */
        private fun JSONObject.text(key: String): String? =
            (opt(key) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_LENGTH }

        /** A whole, non-negative number; anything else is no count. */
        private fun JSONObject.count(key: String): Int? {
            val value: Any? = opt(key)
            if (value is Int) return value.takeIf { it >= 0 }
            if (value is Long) return value.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
            return null
        }
    }
}
