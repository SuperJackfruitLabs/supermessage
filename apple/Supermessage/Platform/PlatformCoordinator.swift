import Foundation
import Observation
import SupermessageFFI
import SupermessageKit
import UIKit
import UserNotifications
import WidgetKit

/// The platform surfaces that sit outside the view tree: notifications,
/// push registration, the Live Activity and the widgets' snapshot.
///
/// Each watches the session's stores through Observation and reacts; none
/// of them changes a store. That is what lets this live beside the views
/// without either knowing about the other.
@MainActor
final class PlatformCoordinator {
    let session: Session
    private let liveActivity = LiveActivityController()

    private var appActive = UIApplication.shared.applicationState == .active
    private var lastPhase: Session.Phase?

    // Notifications
    private var previousRooms: [RoomRow] = []
    private var timelineRoomId: String?
    private var timelineSince: UInt64 = 0
    private var notified: Set<String> = []
    /// Until when roster changes count as catching up rather than news.
    ///
    /// Coming back to the foreground re-seeds every store (see
    /// `Session.scenePhaseChanged`), and the seed arrives as one change
    /// holding everything that happened while the app was suspended. None of
    /// it was posted then, and a burst of it now is not what "a new message"
    /// means.
    private var catchUpUntil = Date.distantPast
    private var modes: [String: (NotificationMode?, Date)] = [:]

    // Push
    private var deviceToken: Data?
    private var registeredPusher: String?

    // Widgets
    /// The App Group's snapshot writer; `nil` in a build without the group.
    private let widgets = WidgetFeed.shared()
    /// The turn the app is watching, for the Agents widget's step.
    private var liveTurn: WidgetLiveTurn?

    init(session: Session) {
        self.session = session
    }

    func start() {
        let center = NotificationCenter.default
        center.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.activeChanged(true) } }
        center.addObserver(
            forName: UIApplication.willResignActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.activeChanged(false) } }

        observe({ [session] in session.phase }) { [weak self] in self?.phaseChanged($0) }
        observe({ [session] in session.rooms.rooms }) { [weak self] in self?.roomsChanged($0) }
        observe({ [session] in
            TimelineSeen(roomId: session.timeline.roomId, revision: session.timeline.revision)
        }) { [weak self] _ in self?.timelineChanged() }
        observe({ [session] in
            LiveSeen(summary: LiveTurnSummary.of(session.live), finished: session.live.finished,
                     roomId: session.timeline.roomId)
        }) { [weak self] in self?.liveChanged($0) }

        // A gate answered from its card: the widgets stop offering it.
        session.decisionAnswered = { [weak self] roomId, eventId, optionId in
            self?.widgetsAnswered(roomId: roomId, eventId: eventId, optionId: optionId)
        }
    }

    // MARK: - Lifecycle

    private func activeChanged(_ active: Bool) {
        appActive = active
        if active { catchUpUntil = Date().addingTimeInterval(5) }
    }

    private func phaseChanged(_ phase: Session.Phase) {
        guard phase != lastPhase else { return }
        defer { lastPhase = phase }
        switch phase {
        case .signedIn:
            // Asked after a sign-in, never at launch: the reader has just
            // chosen to use the app, and the question makes sense then.
            // A restored session only re-registers when permission was
            // already given — it never prompts.
            let justSignedIn = lastPhase == .signedOut
            Task { await self.prepareNotifications(prompt: justSignedIn) }
        case .signedOut:
            if lastPhase == .signedIn {
                LocalNotifier.removeAll()
                liveActivity.endAll()
                // The pusher itself was removed by the core's logout, before
                // the token went; this only forgets that one was registered.
                registeredPusher = nil
                session.pausesSyncInBackground = false
                writeWidgets { $0.signedOut() }
            }
            previousRooms = []
            notified = []
        case .starting:
            break
        }
    }

    private func prepareNotifications(prompt: Bool) async {
        var status = await Self.authorizationStatus()
        if status == .notDetermined, prompt {
            _ = try? await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
            status = await Self.authorizationStatus()
        }
        guard status == .authorized || status == .provisional || status == .ephemeral else { return }
        // Asking for a token is harmless without a gateway — the token is
        // simply not handed to anyone (see `registerPusherIfConfigured`) —
        // and it fails quietly in a build without `aps-environment`.
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// Read off the main actor: `UNNotificationSettings` is not `Sendable`,
    /// and only its status needs to come back.
    nonisolated private static func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    // MARK: - Push

    func deviceTokenReceived(_ token: Data) {
        deviceToken = token
        Task { await registerPusherIfConfigured() }
    }

    private func registerPusherIfConfigured() async {
        guard session.phase == .signedIn, let token = deviceToken,
            let gateway = PushConfiguration.gatewayURL(from: Bundle.main.infoDictionary)
        else { return }
        let hex = PushConfiguration.hex(token)
        guard registeredPusher != hex else { return }
        // The signing profile's APNs environment, not `#if DEBUG`: a Release
        // build is a production token whatever the compiler flags say.
        let registration = PushConfiguration.registration(
            token: token, gateway: gateway,
            bundleId: Bundle.main.bundleIdentifier ?? "dev.supermessage.ios",
            sandbox: PushConfiguration.isSandbox(),
            deviceName: UIDevice.current.name,
            language: Locale.preferredLanguages.first ?? "en")
        if await session.registerPusher(registration) {
            registeredPusher = hex
            // From here the extension shows what arrives while the app is
            // away, and needs the stores' lock to do it.
            session.pausesSyncInBackground = true
        }
    }

    /// Whether a remote notification arriving in the foreground is shown —
    /// see `NotificationComposer.presentsRemote`.
    func presentsRemote(roomId: String?, eventId: String?) -> Bool {
        NotificationComposer.presentsRemote(
            roomId: roomId, eventId: eventId, context: context, alreadyNotified: notified)
    }

    // MARK: - Local notifications

    private var context: NotificationContext {
        NotificationContext(
            openRoomId: session.rooms.selectedId, appActive: appActive,
            timelineRoomId: session.timeline.roomId, remotePush: registeredPusher != nil)
    }

    private func roomsChanged(_ rooms: [RoomRow]) {
        defer { previousRooms = rooms }
        writeWidgetSnapshot(rooms)
        guard session.phase == .signedIn, Date() >= catchUpUntil else { return }
        let notes = NotificationComposer.forRoster(
            previous: previousRooms, next: rooms, context: context)
        deliver(notes)
    }

    private func timelineChanged() {
        widgetsSawTimeline()
        let roomId = session.timeline.roomId
        if roomId != timelineRoomId {
            timelineRoomId = roomId
            timelineSince = Self.milliseconds(Date())
        }
        if Date() < catchUpUntil { timelineSince = max(timelineSince, Self.milliseconds(Date())) }
        guard session.phase == .signedIn, let roomId else { return }
        let name = session.rooms.row(for: roomId)?.identity.name ?? session.rooms.selectedName ?? ""
        let notes = NotificationComposer.forTimeline(
            roomId: roomId, roomName: name, rows: session.timeline.items, since: timelineSince,
            alreadyNotified: notified, context: context)
        // Recorded now, whether or not the room's setting lets them through:
        // "not delivered" is an answer too, and asking again on every
        // reaction would be the same question.
        for note in notes { if let id = note.eventId { notified.insert(id) } }
        deliver(notes)
    }

    private func deliver(_ notes: [LocalNotification]) {
        guard !notes.isEmpty else { return }
        Task {
            for note in notes {
                let mode = await self.mode(of: note.roomId)
                if NotificationComposer.delivers(note, mode: mode) { LocalNotifier.post(note) }
            }
        }
    }

    /// A room's notification setting, remembered for a minute. It is a
    /// `roomInfo` round trip, and a busy room would otherwise ask per message.
    private func mode(of roomId: String) async -> NotificationMode? {
        if let (mode, at) = modes[roomId], Date().timeIntervalSince(at) < 60 { return mode }
        let mode = await session.notificationMode(of: roomId)
        modes[roomId] = (mode, Date())
        return mode
    }

    // MARK: - Live Activity

    private func liveChanged(_ seen: LiveSeen) {
        let name = seen.roomId.flatMap { session.rooms.row(for: $0)?.identity.name }
            ?? session.rooms.selectedName ?? "Agent"
        liveActivity.update(
            summary: seen.summary, finished: seen.finished, roomId: seen.roomId, agentName: name)
        let turn = seen.summary.flatMap { summary in
            seen.roomId.map { WidgetLiveTurn(roomId: $0, step: summary.step) }
        }
        if turn != liveTurn {
            liveTurn = turn
            writeWidgetSnapshot(session.rooms.rooms)
        }
    }

    // MARK: - Widgets

    /// The roster, as the widgets' authoritative picture of the agents — the
    /// core merges it over whatever pushes wrote while the app was away
    /// (`widget::apply_roster`), and an older roster never replaces a newer.
    private func writeWidgetSnapshot(_ rooms: [RoomRow]) {
        guard session.phase == .signedIn else { return }
        let asOf = Self.milliseconds(Date())
        let live = liveTurn.map { [$0] } ?? []
        writeWidgets { $0.apply(rows: rooms, live: live, asOf: asOf) }
    }

    /// The open room can see what no push says: a card the board resolved,
    /// or a permission answered in the room. Read only when the widgets hold
    /// a decision for that room — a streaming answer re-emits the timeline
    /// several times a second, and nearly always there is nothing to check.
    private func widgetsSawTimeline() {
        guard session.phase == .signedIn, let roomId = session.timeline.roomId,
            WidgetSnapshotStore.read()?.decisions.contains(where: { $0.roomId == roomId }) == true
        else { return }
        let rows = session.timeline.items
        writeWidgets { $0.apply(roomId: roomId, timeline: rows) }
    }

    private func widgetsAnswered(roomId: String, eventId: String, optionId: String) {
        writeWidgets { $0.markAnswered(roomId: roomId, eventId: eventId, optionId: optionId) }
    }

    /// Run one write and reload the widgets when the core says it is worth it.
    private func writeWidgets(_ write: (WidgetFeed) -> WidgetFeed.Reload) {
        guard let widgets, write(widgets) else { return }
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// A background refresh (`WidgetRefresh`): catch up briefly, then write
    /// the roster as it now stands.
    func refreshWidgetsInBackground() async -> Bool {
        let caughtUp = await session.catchUpInBackground(for: .seconds(8))
        writeWidgetSnapshot(session.rooms.rooms)
        return caughtUp
    }

    private static func milliseconds(_ date: Date) -> UInt64 {
        UInt64(max(0, date.timeIntervalSince1970 * 1000))
    }
}

private struct TimelineSeen: Equatable {
    let roomId: String?
    let revision: UInt64
}

private struct LiveSeen: Equatable {
    let summary: LiveTurnSummary?
    let finished: Bool
    let roomId: String?
}

/// Call `changed` with `read()` now and whenever what it read changes.
///
/// `withObservationTracking` fires once per registration, so this
/// re-registers after each change. A store may touch a property without
/// changing it, so every `changed` above tolerates hearing the same value
/// twice.
@MainActor
private func observe<T>(
    _ read: @escaping @MainActor @Sendable () -> T,
    _ changed: @escaping @MainActor @Sendable (T) -> Void
) {
    let value = withObservationTracking {
        read()
    } onChange: {
        Task { @MainActor in observe(read, changed) }
    }
    changed(value)
}
