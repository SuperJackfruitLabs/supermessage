package dev.supermessage.kit

import dev.supermessage.kit.stores.AvatarCache
import dev.supermessage.kit.stores.ConnectionStore
import dev.supermessage.kit.stores.DraftStore
import dev.supermessage.kit.stores.EditTarget
import dev.supermessage.kit.stores.LiveStore
import dev.supermessage.kit.stores.MediaCache
import dev.supermessage.kit.stores.ReplyTarget
import dev.supermessage.kit.stores.RoomsStore
import dev.supermessage.kit.stores.SpacesStore
import dev.supermessage.kit.stores.StagedAttachment
import dev.supermessage.kit.stores.TimelineStore
import dev.supermessage.kit.stores.TypingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.FfiException
import uniffi.supermessage_ffi.collectMentions

/**
 * Everything the app has: the core, the pump, and the stores the screens
 * read.
 *
 * ## The one drain task
 *
 * [start] and [signIn] each end by calling [beginDraining], which launches
 * **exactly one** coroutine that collects [EventPump.events] and hands each
 * event to a store. That single collector is the whole ordering guarantee:
 * a `DiffEnvelope` carries a `seq`, and applying diffs out of order corrupts
 * the reader's view in a way that presents as a rendering bug. A second
 * collector, or a coroutine launched per event, breaks it — see `EventPump`'s
 * own doc comment, and this class's own `SessionTest` for the burst that
 * pins it.
 *
 * The job lives as long as the stream. [EventPump.finish] on logout ends the
 * collection, and the job with it.
 *
 * ## Where this differs from `apple/SupermessageKit/Session.swift`
 *
 * - **`@MainActor @Observable` becomes [phase] and [failure] exposed as
 *   [StateFlow]s**, matching every store under `stores/` (`RoomsStore`,
 *   `TypingStore`, `SpacesStore`, and the rest) — see [DraftStore]'s doc
 *   comment for why `@MainActor` becomes a documented, not checked,
 *   invariant here rather than a compiler-enforced one. `Session` itself is
 *   the same translation at the top of the object graph that every store
 *   already carries at the leaves.
 * - **Takes a built [CoreClient], not a data directory.** Swift's
 *   `convenience init()` calls `CoreClient(dataDirectory: CoreClient.dataDirectory())`,
 *   which asks `FileManager` for the app's own container — a decision
 *   `CoreClient`'s own port (Task 7) already pushed out of `:kit` entirely,
 *   since answering it needs a `Context` this module deliberately does not
 *   depend on. There is therefore no Android equivalent of that convenience
 *   initializer here: whichever layer has a `Context` (`:app`) builds the
 *   real [uniffi.supermessage_ffi.CoreInterface], wraps it in a [CoreClient],
 *   and hands the already-built client to this constructor — the same shape
 *   [CoreClient] itself already takes a [uniffi.supermessage_ffi.CoreInterface]
 *   rather than a directory.
 * - **Takes a [CoroutineScope]**, where Swift's `@MainActor` class needs
 *   none: every `Task` on iOS is implicitly attached to the actor. Kotlin has
 *   no compiler-enforced main-thread isolation to mirror that with, so
 *   [scope] is the explicit stand-in — the same role it plays for
 *   `RoomsStore`, `TimelineStore` and `MediaCache`, all of which this class
 *   constructs and supplies it to. It is where [beginDraining] launches the
 *   one drain job, and a test controls it directly so it can assert on that
 *   job's lifecycle rather than guessing at it. **Confinement is
 *   documented, not checked**, the same discipline every store under
 *   `stores/` already carries: every method here that touches [phase],
 *   [failure] or [drainJob] is meant to run on the same single thread of
 *   execution [scope] supplies, including the drain job calling back into
 *   [handle].
 * - **`CancellationException` is rethrown before the broad catch**, in every
 *   place Swift's untyped `catch {}` or `try?` would otherwise be mirrored
 *   by a Kotlin `catch (e: Exception)` wide enough to swallow it. This is
 *   the same divergence `SpacesStore` and `TimelineStore` already document:
 *   `CancellationException` is itself an `Exception` on the JVM, and
 *   catching it here would let a cancelled coroutine report as having
 *   completed normally.
 * - **[pump] and [drainJob] are `internal val`/`internal var`, not
 *   `private`**, for the reason `CoreClient.dispatcher` already is: a test
 *   needs to push events directly into the pump to exercise the drain, and
 *   needs to read the job back to assert it actually finishes on `signOut`
 *   and actually restarts on a later `signIn`. Nothing outside this module
 *   sees either.
 * - **[start] and [signIn] both undo a prior `signOut`'s teardown — or a
 *   still-active drain, if there was no `signOut` at all — before handing
 *   anything back to the core** — `pump.finish()`, `drainJob?.cancel()` and
 *   `drainJob = null` (the same three lines `signOut` itself runs), then
 *   `pump.reset()` and `rooms.resume()`, all before [pump] is registered via
 *   `restoreSession`/`login`. Three independent instances of the same defect
 *   class, all absent from Swift, all confirmed rather than assumed:
 *   - **`pump.reset()`.** Swift's [pump] is a `private let` built once and
 *     never touched again — `apple/SupermessageKit/Session.swift:48` —
 *     because a Swift `AsyncStream` has no equivalent gap to fall into
 *     here. A Kotlin `Channel`, once [EventPump.finish] closes it on
 *     `signOut`, cannot be reopened: re-registering the same,
 *     already-finished [pump] with the core on a later [signIn] would
 *     produce a collector that completes immediately over a dead channel,
 *     with no error and no crash — just silence, on every event from then
 *     on. `EventPump.reset` recreates only the pump's internal channel, not
 *     the [EventPump] object itself, so [timeline]'s own captured `sink`
 *     reference (set once, at construction) never goes stale and never
 *     needs to be reconstructed. See `EventPump`'s own KDoc for the full
 *     reasoning. iOS carries the identical latent bug: a single `Session`
 *     living for the app's process lifetime
 *     (`apple/Supermessage/RootView.swift:12`), signing out and back in on
 *     that same instance (`apple/Supermessage/Panels/AccountPanel.swift:67`),
 *     with nothing in `EventPump.swift` that plays [reset]'s role.
 *   - **The `pump.finish()`/`drainJob?.cancel()`/`drainJob = null` teardown
 *     ahead of `pump.reset()`.** A narrower, later find than the two above:
 *     [reset] by itself only swaps [EventPump]'s channel, and does nothing
 *     about whichever coroutine is still collecting the old one. Calling
 *     [start] or [signIn] on a `Session` that is already signed in and
 *     draining — no `signOut` in between — left that collector suspended on
 *     the orphaned old channel forever, while [beginDraining]'s own
 *     `drainJob != null` guard saw a still-non-null, still-active job and
 *     never started a new one on the fresh channel. Every event pushed
 *     afterward landed in a channel nobody was reading — the identical
 *     silent-failure shape as the `pump.reset()` defect above, one call
 *     earlier. Running `signOut`'s own teardown unconditionally here closes
 *     it: `finish`, `cancel` and setting [drainJob] to `null` are each
 *     idempotent on an already-torn-down pump, so repeating them costs
 *     nothing when a real `signOut` already ran. See `EventPump.reset`'s own
 *     KDoc for the full reasoning.
 *   - **`rooms.resume()`.** [rooms] and [timeline] are each built once, here,
 *     for this object's whole lifetime, and each owns a `GapSync` whose
 *     `stop()` — called from `clear()` on every `signOut` — was, until this
 *     fix, one-way: nothing ever cleared it, so a later `signIn`'s `seed`
 *     silently did nothing, forever, on both stores. `GapSync.resume` (and
 *     the `stop`/`resetForNewSubscription` it now integrates with) is where
 *     the actual fix lives; see that class's KDoc for the full reasoning,
 *     including why `timeline` needed no equivalent call here —
 *     `TimelineStore.subscribeTo` already reaches the same recovery through
 *     `resetForNewSubscription`, on every room it opens, including the
 *     first one after a sign-in. This too is confirmed present on iOS, on
 *     both stores: `GapSync.swift` has no `resume` equivalent either, and
 *     `apple/SupermessageKit/Stores/RoomsStore.swift` and
 *     `.../TimelineStore.swift` each build their one `GapSync` once, inside
 *     `init`, never rebuilt.
 *
 *   This class's `SessionTest` proves the pump fix and the timeline half of
 *   the latch fix end to end — the sign-out/sign-in cycle it drives pushes
 *   both a `Connection` event (isolating the pump fix from the latch fix)
 *   and a `TimelineDiff` (the actual symptom a reader would notice: sign
 *   out, sign back in, open a room, see nothing) — and a second test drives
 *   the sign-in/sign-in cycle the same way to pin the drain-teardown fix.
 *   **It does not exercise [rooms] directly**, so it is not evidence for
 *   `RoomsStore.resume`; that half is `RoomsStoreTest`'s and `GapSyncTest`'s
 *   to prove, and they do.
 *
 * Twelve stores are constructed below, not eleven — [avatars] and [faces]
 * are two separate [AvatarCache] instances, one keyed by room id and one
 * (via [AvatarCache.forMembers]) by a message sender's `mxc:` URI.
 */
class Session(
    private val client: CoreClient,
    private val scope: CoroutineScope,
) {
    enum class Phase {
        /** Before [start] has answered. */
        STARTING,
        SIGNED_OUT,
        SIGNED_IN,
    }

    private val _phase = MutableStateFlow(Phase.STARTING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The last thing worth telling the reader about, or `null`. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    val connection = ConnectionStore()
    val rooms: RoomsStore = RoomsStore(client = client, scope = scope)
    val spaces: SpacesStore = SpacesStore(client = client)
    val avatars: AvatarCache = AvatarCache(client = client)

    /** Senders' faces, keyed by `mxc:` URI — see [AvatarCache.forMembers]. */
    val faces: AvatarCache = AvatarCache.forMembers(client = client)
    val media: MediaCache = MediaCache(client = client, scope = scope)

    /** See this class's KDoc for why [pump] is `internal`, not `private`. */
    internal val pump = EventPump()
    val timeline: TimelineStore = TimelineStore(client = client, sink = pump, scope = scope)
    val live = LiveStore()
    val typing = TypingStore()
    val drafts = DraftStore()
    val replies = ReplyTarget()
    val edits = EditTarget()
    val staged: StagedAttachment = StagedAttachment(client = client)

    /** See this class's KDoc for why [drainJob] is `internal`, not `private`. */
    internal var drainJob: Job? = null
        private set

    /**
     * Restore a stored session, if there is one.
     *
     * Credentials live wherever the platform's own secure storage is — the
     * core configures it, and this app never sees them.
     */
    suspend fun start(): Boolean {
        return try {
            // Undo whatever a prior signOut tore down, before anything
            // from the core can possibly arrive: a fresh channel (see
            // EventPump.reset's KDoc for why a pump a prior signOut already
            // finish()ed must not be handed back to the core as-is), and a
            // roster no longer permanently latched off (see
            // RoomsStore.resume's KDoc — timeline needs no equivalent call
            // here; see GapSync.resume's KDoc for why).
            //
            // The teardown below — finish, cancel, clear — runs
            // unconditionally, not only after a signOut: `EventPump.reset`
            // alone only swaps the channel, and if this Session was already
            // signed in and draining when start()/signIn() ran again, the
            // old collector would be left suspended on an orphaned channel
            // forever, with `drainJob` still non-null so `beginDraining`
            // below would never start a new one — silence, not a crash. This
            // is the exact same teardown `signOut` performs, made safe to
            // repeat here because `finish`, `cancel` and `drainJob = null`
            // are each idempotent on an already-torn-down pump.
            pump.finish()
            drainJob?.cancel()
            drainJob = null
            pump.reset()
            rooms.resume()
            val restored = client.restoreSession(sink = pump)
            _phase.value = if (restored) Phase.SIGNED_IN else Phase.SIGNED_OUT
            if (restored) {
                beginDraining()
                load()
            }
            restored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure to *restore* is not a failure to sign in: there may
            // simply be nothing stored. Either way the answer is the login
            // screen, and saying more than that would be guessing.
            _phase.value = Phase.SIGNED_OUT
            false
        }
    }

    suspend fun signIn(homeserver: String, username: String, password: String) {
        _failure.value = null
        try {
            // Same reason as start(): undo a prior signOut's teardown — or a
            // still-active drain, if this is called again without one —
            // before the core can possibly start talking again. See start()'s
            // own comment for why this runs unconditionally rather than only
            // after a signOut.
            pump.finish()
            drainJob?.cancel()
            drainJob = null
            pump.reset()
            rooms.resume()
            client.login(homeserver = homeserver, username = username, password = password, sink = pump)
            _phase.value = Phase.SIGNED_IN
            beginDraining()
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FfiException) {
            _failure.value = ErrorPresenter.message(e)
        } catch (e: Exception) {
            _failure.value = "Couldn't sign in."
        }
    }

    /**
     * Send what is in the composer: the text, the attachment, or both.
     *
     * Returns a message when the core refuses, or `null`. Mentions are the
     * core's — [collectMentions] produces the `m.mentions` an agent reads to
     * decide a message in a room full of agents was addressed to it, and
     * this app must not have a second opinion about that.
     */
    suspend fun send(text: String, roomId: String): String? {
        val body = text.trim()

        if (staged.file.value != null) {
            val failure = staged.send(roomId = roomId)
            if (failure != null) return failure
        }
        if (body.isEmpty()) return null

        return try {
            val reply = replies.pending(roomId)
            if (reply != null) {
                client.sendReply(roomId = roomId, body = body, inReplyTo = reply.eventId)
                replies.cancel(roomId)
            } else {
                val mentions = collectMentions(text = body, members = emptyList())
                client.sendMessage(roomId = roomId, body = body, mentions = mentions)
            }
            setTyping(false, roomId)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: FfiException) {
            ErrorPresenter.message(e)
        } catch (e: Exception) {
            "Couldn't send that."
        }
    }

    /**
     * Tell the room whether this account is typing.
     *
     * Failures are swallowed: a typing notice nobody saw is not worth an
     * alert, and the composer is the last place to interrupt someone.
     */
    suspend fun setTyping(typing: Boolean, roomId: String) {
        try {
            client.setTyping(roomId = roomId, typing = typing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mirrors Swift's `try?`.
        }
    }

    /**
     * Foreground and background.
     *
     * **The one thing a phone needs that desktop never did.** A suspended
     * app loses its sockets, and the `sm://` channels only speak when
     * something *changes* — so a store that came back to a quiet account
     * would sit empty until the next message, which in these rooms can be
     * hours. This is exactly what `seed()` was written for.
     */
    suspend fun scenePhaseChanged(active: Boolean) {
        if (_phase.value != Phase.SIGNED_IN) return
        if (active) {
            rooms.seed()
            timeline.seed()
            spaces.refresh()
        } else {
            val roomId = timeline.roomId.value
            if (roomId != null) {
                // Leaving a typing notice on when the app goes away tells
                // the room someone is writing who is not even looking at it.
                setTyping(false, roomId)
            }
        }
    }

    // The commands the panels drive.
    //
    // Each returns a message on refusal rather than throwing, because a
    // panel shows the failure inline rather than propagating it — the
    // reader is in the middle of something and an alert would take the
    // room away.

    suspend fun joinRoom(roomId: String): String? = refusal { client.joinRoom(roomId) }

    suspend fun leaveRoom(roomId: String): String? = refusal { client.leaveRoom(roomId) }

    /**
     * Add or remove one of this account's reactions.
     *
     * Takes the **event** id: a reaction is an `m.annotation` pointing at an
     * event, and a message the server has not acknowledged has none. The
     * affordance is hidden in that state (`canReplyOrReact`), so `null` here
     * means something raced, and doing nothing is the honest answer rather
     * than sending against an id the homeserver never issued.
     */
    suspend fun toggleReaction(eventId: String?, key: String, roomId: String) {
        if (eventId == null) return
        try {
            client.toggleReaction(roomId = roomId, eventId = eventId, key = key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mirrors Swift's `_ = try?`.
        }
    }

    /**
     * Rewrite a message this account sent.
     *
     * Takes the **event** id for the same reason [toggleReaction] does: an
     * edit is a relation pointing at an event, and a message the homeserver
     * has not acknowledged has none.
     *
     * Returns whether it landed, so a caller can leave the reader's text in
     * the composer rather than discarding it into a failure.
     */
    suspend fun edit(eventId: String?, body: String, roomId: String): Boolean {
        if (eventId == null) return false
        return try {
            client.editMessage(roomId = roomId, eventId = eventId, body = body)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Delete a message. A Matrix redaction: permanent, and visible to
     * everyone in the room.
     */
    suspend fun delete(eventId: String?, roomId: String): Boolean {
        if (eventId == null) return false
        return try {
            client.deleteMessage(roomId = roomId, eventId = eventId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Set how loudly a room may interrupt.
     *
     * [NotificationMode.DEFAULT] unsets this room's own rule rather than
     * writing today's account default into it. Returns whether it landed,
     * so a control can put itself back rather than showing a setting the
     * homeserver never accepted.
     */
    suspend fun setNotifications(mode: NotificationMode, roomId: String): Boolean = try {
        client.setRoomNotifications(roomId = roomId, mode = mode)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Pin or unpin a room. The `m.favourite` tag, so it travels to other
     * clients rather than living only on this phone.
     */
    suspend fun setPinned(pinned: Boolean, roomId: String): Boolean = try {
        client.setRoomPinned(roomId = roomId, pinned = pinned)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Who invited this account to [roomId], or `null`. */
    suspend fun inviter(roomId: String): String? = try {
        client.roomInviter(roomId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Who this app is signed in as, and where. */
    suspend fun account(): AccountDto? = try {
        client.account()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun roomInfo(roomId: String): RoomInfoDto = client.roomInfo(roomId)

    /**
     * Search for [term], in [roomId] when one is given and across every
     * room this account can see otherwise.
     *
     * Unlike most of this file's read paths, a failure here is **not**
     * swallowed: it is [roomInfo]'s own contract, not [people]'s or
     * [account]'s. A homeserver error, an expired token or a dropped
     * connection must not render as "no results" — the caller (`SearchPanel`
     * on both platforms) is the one that can tell a reader apart from an
     * empty list, and it does, by catching this and mapping it through
     * `ErrorPresenter` into `SearchState.Failed`.
     */
    suspend fun search(term: String, roomId: String? = null): List<SearchResultDto> =
        client.searchMessages(term = term, roomId = roomId)

    sealed class Outcome {
        data class Success(val roomId: String) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    /**
     * A room's avatar at its original size, for looking at the picture.
     *
     * Not the roster's cache: that holds a 96px thumbnail, which is right
     * for a circle in a list and four times too small the moment someone
     * opens it. Fetched on demand, and the SDK's media store means opening
     * the same picture twice hits the network once.
     */
    suspend fun fullAvatar(roomId: String): String? = try {
        client.roomAvatarFull(roomId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Everyone this account shares a room with, agents first. */
    suspend fun people(): List<PersonDto> = try {
        client.knownPeople()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Open the conversation with [person], creating it only if there is not
     * one already.
     *
     * Reusing an existing one-to-one is the whole point: tapping an agent's
     * name twice should return the reader to the conversation they had, not
     * leave a roster of identically named rooms with the history scattered
     * between them.
     *
     * When there is no existing room this falls through to [createRoom],
     * which — on success — reconciles rooms and spaces via `load()` before
     * returning. Reusing an existing room does not: only the create path
     * does.
     */
    suspend fun openConversation(person: PersonDto): Outcome {
        val existing = try {
            client.directRoomWith(person.userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (existing != null) return Outcome.Success(existing)
        return createRoom(name = person.name, invite = listOf(person.userId))
    }

    /**
     * Create a room named [name], inviting [invite].
     *
     * On success this also calls `load()`, reconciling rooms and spaces —
     * a side effect that is easy to miss because nothing in the return type
     * says so: a caller reading only [Outcome] would not learn that the
     * roster and space list were just re-seeded.
     */
    suspend fun createRoom(name: String, invite: List<String>): Outcome = try {
        val roomId = client.createRoom(name = name, invite = invite, isDirect = invite.isNotEmpty())
        load()
        Outcome.Success(roomId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: FfiException) {
        Outcome.Failure(ErrorPresenter.message(e))
    } catch (e: Exception) {
        Outcome.Failure("Couldn't create that room.")
    }

    suspend fun joinByAlias(aliasOrId: String): Outcome = try {
        val roomId = client.joinRoomByAlias(aliasOrId)
        load()
        Outcome.Success(roomId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: FfiException) {
        Outcome.Failure(ErrorPresenter.message(e))
    } catch (e: Exception) {
        Outcome.Failure("Couldn't join that room.")
    }

    private suspend fun refusal(body: suspend () -> Unit): String? = try {
        body()
        load()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: FfiException) {
        ErrorPresenter.message(e)
    } catch (e: Exception) {
        "That didn't work."
    }

    /**
     * Open a room: the timeline subscribes, and the transient stores are
     * re-pointed so nothing from the last room survives the switch.
     *
     * Idempotent for the room already open — [TimelineStore.subscribeTo]'s
     * own guard, not one repeated here.
     */
    suspend fun open(roomId: String) {
        live.focus(roomId)
        typing.focus(roomId)
        timeline.subscribeTo(roomId)
    }

    /**
     * Ask for the state the channels will not volunteer.
     *
     * The diff channels only speak when something *changes*, so a store
     * built after the core has already emitted its opening state would sit
     * empty until the next message — minutes, in a quiet account. Seeding is
     * how it asks. See `GapSync.seed`.
     */
    private suspend fun load() {
        rooms.seed()
        spaces.refresh()
    }

    suspend fun signOut() {
        try {
            client.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mirrors Swift's `try?`.
        }
        pump.finish()
        drainJob?.cancel()
        drainJob = null
        rooms.clear()
        timeline.clear()
        live.clear()
        typing.focus(null)
        drafts.clearAll()
        replies.clearAll()
        staged.discard()
        spaces.clear()
        avatars.clear()
        faces.clear()
        edits.clearAll()
        _phase.value = Phase.SIGNED_OUT
    }

    /** The single collector. See this class's KDoc on ordering. */
    private fun beginDraining() {
        if (drainJob != null) return
        drainJob = scope.launch {
            pump.events.collect { event -> handle(event) }
        }
    }

    /**
     * Route one event to the store that owns it.
     *
     * An exhaustive `when` used as an expression, with no `else`: a new
     * variant on the boundary should break this build rather than be
     * dropped on the floor, the same discipline [DiffApply]'s `.generic`
     * conversions already use, and the same reason the Rust side's
     * `CoreEvent` is a closed enum.
     */
    private fun handle(event: FfiEvent): Unit = when (event) {
        is FfiEvent.Connection -> connection.apply(event.state)
        is FfiEvent.RoomsDiff -> rooms.handle(event.envelope)
        is FfiEvent.TimelineDiff -> {
            timeline.handle(event.envelope)
            // A message from someone is better evidence that they stopped
            // typing than the server-side timeout on the notice — see
            // `TypingStore.messagesArrived`. Own messages are excluded: this
            // reader's own send says nothing about who else is writing.
            // **Ids, not names.** `TimelineRow.senderName` is the composed
            // attribution — "Super Chotu (Hermes on Guild)" — and the typing
            // store holds whichever `label` the core gave it ("Super
            // Chotu"). Matching one of those two strings against the other
            // is how the indicator got stuck for minutes after the reply
            // landed on iOS; `item.sender`, the raw user id, is what both
            // sides can actually agree on.
            val spoke = event.envelope.ops
                .map { it.generic }
                .flatMap { opValues(it) }
                .filter { !it.item.isOwn }
                .mapNotNull { it.item.sender }
            if (spoke.isNotEmpty()) typing.messagesArrived(spoke) else Unit
        }
        is FfiEvent.Typing -> typing.handle(roomId = event.roomId, users = event.users)
        is FfiEvent.Live ->
            live.handleLive(roomId = event.roomId, seq = event.seq, text = event.text, done = event.done)
        is FfiEvent.Thought ->
            live.handleThought(roomId = event.roomId, seq = event.seq, text = event.text, done = event.done)
        is FfiEvent.Tool ->
            live.handleTool(
                roomId = event.roomId, seq = event.seq, toolCallId = event.toolCallId, title = event.title,
                kind = event.kind, status = event.status, locations = event.locations, input = event.input,
                output = event.output,
            )
        is FfiEvent.AttachmentStaged -> {
            // Handled by the composer, which owns the staged strip. Listed
            // rather than swept into an `else` so a new variant on the
            // boundary still breaks this build.
        }
    }
}
