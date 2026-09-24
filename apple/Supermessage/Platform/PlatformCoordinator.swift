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
    private var lastSnapshot: WidgetSnapshot?

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
                registeredPusher = nil
                writeSnapshot(.empty)
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
        #if DEBUG
            let sandbox = true
        #else
            let sandbox = false
        #endif
        let registration = PushConfiguration.registration(
            token: token, gateway: gateway,
            bundleId: Bundle.main.bundleIdentifier ?? "dev.supermessage.ios", sandbox: sandbox,
            deviceName: UIDevice.current.name,
            language: Locale.preferredLanguages.first ?? "en")
        if await session.registerPusher(registration) { registeredPusher = hex }
    }

    // MARK: - Local notifications

    private var context: NotificationContext {
        NotificationContext(
            openRoomId: session.rooms.selectedId, appActive: appActive,
            timelineRoomId: session.timeline.roomId)
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

    /// A notification's action, decoded.
    func respond(to response: NotificationKeys.Response) async {
        switch response {
        case .ignore:
            break
        case let .open(roomId):
            NotificationRouter.shared.request(roomId: roomId)
        case let .answer(roomId, optionId):
            // An action can wake an app that is not running, and the session
            // restores on its own schedule. Wait for it rather than sending
            // into a core that has no client yet.
            for _ in 0..<40 where session.phase != .signedIn {
                try? await Task.sleep(for: .milliseconds(500))
            }
            if !(await session.answerPermission(optionId: optionId, in: roomId)) {
                LocalNotifier.postFailure(roomId: roomId)
            }
        }
    }

    // MARK: - Live Activity

    private func liveChanged(_ seen: LiveSeen) {
        let name = seen.roomId.flatMap { session.rooms.row(for: $0)?.identity.name }
            ?? session.rooms.selectedName ?? "Agent"
        liveActivity.update(
            summary: seen.summary, finished: seen.finished, roomId: seen.roomId, agentName: name)
    }

    // MARK: - Widgets

    private func writeWidgetSnapshot(_ rooms: [RoomRow]) {
        guard session.phase == .signedIn else { return }
        writeSnapshot(WidgetSummary.snapshot(rows: rooms, now: Date()))
    }

    private func writeSnapshot(_ snapshot: WidgetSnapshot) {
        if let lastSnapshot, lastSnapshot.sameContent(as: snapshot) { return }
        lastSnapshot = snapshot
        if WidgetSnapshotStore.write(snapshot) { WidgetCenter.shared.reloadAllTimelines() }
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
