import SupermessageFFI
import SupermessageKit
import SwiftUI

/// The first-run demo: a sample agent, Atlas, asks a permission, and the
/// reader approves it.
///
/// **Nothing here is a Matrix room and nothing is sent.** There is no
/// `Session` in this view — it cannot reach the network, which is a stronger
/// promise than a flag saying it will not. The card is the real
/// `CustomEventCard`, handed an answer closure that only moves the local
/// `FirstRunDemo` along, so what the reader learns to tap is the thing they
/// will tap in a real room.
///
/// Shown once, after the first interactive sign-in, and skippable at every
/// step.
struct FirstRunDemoView: View {
    let onFinish: () -> Void

    @State private var demo = FirstRunDemo()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(demo.script.banner)
                        .metaFace()
                        .foregroundStyle(Theme.contentFaint)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.bottom, 4)

                    if demo.step == .greeting {
                        typing
                    } else {
                        message
                            .transition(arrival)
                    }

                    if demo.step == .waiting || demo.step == .approved {
                        CustomEventCard(
                            view: demo.script.card, label: demo.script.cardLabel,
                            eventType: demo.script.cardEventType,
                            senderName: demo.script.agentName,
                            onDecide: { answer in demo.answer(answer.optionId) })
                            .transition(arrival)
                    }

                    if demo.step == .approved {
                        VStack(spacing: 14) {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 40))
                                .foregroundStyle(Theme.ok)
                                .symbolEffect(.bounce, options: .nonRepeating, value: demo.step)
                                .accessibilityHidden(true)
                            Text(demo.script.approvedTitle)
                                .font(.title3.weight(.semibold))
                                .multilineTextAlignment(.center)
                            Text(demo.script.approvedBody)
                                .font(.subheadline)
                                .foregroundStyle(Theme.contentMuted)
                                .multilineTextAlignment(.center)
                            Button(action: onFinish) {
                                Text(demo.script.finishLabel).font(.headline).frame(maxWidth: .infinity)
                                    .foregroundStyle(Theme.accentContent)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .tint(Theme.accent)
                            .padding(.top, 4)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 12)
                        .transition(arrival)
                    }
                }
                .padding(16)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .animation(reduceMotion ? nil : .spring(duration: 0.45, bounce: 0.2), value: demo.step)
            }
            .background(Theme.surface)
            .navigationTitle(demo.script.agentName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if demo.step != .approved {
                        Button(demo.script.skipLabel, action: onFinish)
                    }
                }
            }
        }
        .sensoryFeedback(.success, trigger: demo.step == .approved)
        .task {
            // Paced so each arrival is read, not skimmed. Under Reduce Motion
            // the pace stays — it is reading time, not animation.
            for delay in demo.script.stepDelaysMs {
                try? await Task.sleep(for: .milliseconds(delay))
                demo.advance()
            }
        }
    }

    private var arrival: AnyTransition {
        reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity)
    }

    private var avatar: some View {
        RoomAvatar(
            roomId: demo.script.roomId, initial: demo.script.initial, avatarURI: nil, describesAgent: true,
            state: .active, size: 32)
    }

    private var typing: some View {
        HStack(spacing: 10) {
            avatar
            Image(systemName: "ellipsis")
                .font(.title3.weight(.bold))
                .foregroundStyle(Theme.contentMuted)
                .symbolEffect(.variableColor.iterative, options: .repeating, isActive: !reduceMotion)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
                .accessibilityLabel(demo.script.typingLabel)
        }
    }

    private var message: some View {
        HStack(alignment: .top, spacing: 10) {
            avatar
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(demo.script.agentName).nameFace()
                    Text(demo.script.agentTag)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(Theme.accentSoft, in: Capsule())
                }
                Text(demo.script.greeting)
                    .font(Theme.body)
                    .padding(12)
                    .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }
}

#if DEBUG
#Preview("First-run demo") {
    FirstRunDemoView {}
        .previewChrome()
}

#Preview("First-run demo, dark") {
    FirstRunDemoView {}
        .preferredColorScheme(.dark)
        .previewChrome()
}
#endif
