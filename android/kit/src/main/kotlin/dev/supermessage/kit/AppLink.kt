package dev.supermessage.kit

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The `supermessage://` link, in the shape iOS shares with its widgets
 * (`WidgetSnapshot.swift` `AppLink`): `supermessage://open?room=<id>[&event=<id>]`.
 *
 * The room is a query item because a Matrix id carries `!` and `:`. Only this app's own link is
 * parsed here; matrix.to and `matrix:` links are the core's (`parse_matrix_link`), and the core has
 * no rule for this scheme, so there is nothing to defer to. The room id stays opaque: what opening
 * it means is the core's.
 */
object AppLink {
    const val SCHEME = "supermessage"
    private const val HOST = "open"

    fun room(roomId: String): String = "$SCHEME://$HOST?room=${encode(roomId)}"

    /** A link to the card for [eventId] in [roomId]; it opens the room, as iOS's does. */
    fun decision(roomId: String, eventId: String): String =
        "${room(roomId)}&event=${encode(eventId)}"

    /** The room a link names, or `null` when [uri] is not a link this app opens. Never throws. */
    fun roomId(uri: String): String? {
        val parsed = try {
            URI(uri)
        } catch (e: Exception) {
            return null
        }
        if (parsed.scheme != SCHEME || parsed.host != HOST) return null
        val raw = parsed.rawQuery ?: return null
        for (item in raw.split('&')) {
            val at = item.indexOf('=')
            if (at < 0 || item.substring(0, at) != "room") continue
            val value = try {
                // '+' is a literal plus in a Matrix id's query, not a space.
                URLDecoder.decode(item.substring(at + 1).replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                return null
            }
            return value.ifEmpty { null }
        }
        return null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
