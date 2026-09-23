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
                    Text("A sample conversation — nothing here is sent anywhere.")
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

                    if demo.step >= .waiting {
                        CustomEventCard(
                            view: Self.card, label: "Permission",
                            eventType: "dev.agentpod.permission.request.v1",
                            senderName: FirstRunDemo.agentName,
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
                            Text("That's it — approvals from chat.")
                                .font(.title3.weight(.semibold))
                                .multilineTextAlignment(.center)
                            Text("When an agent needs you, it asks in its room, and the Needs you tab keeps the list.")
                                .font(.subheadline)
                                .foregroundStyle(Theme.contentMuted)
                                .multilineTextAlignment(.center)
                            Button(action: onFinish) {
                                Text("Get started").font(.headline).frame(maxWidth: .infinity)
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
            .navigationTitle(FirstRunDemo.agentName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if demo.step != .approved {
                        Button("Skip", action: onFinish)
                    }
                }
            }
        }
        .sensoryFeedback(.success, trigger: demo.step == .approved)
        .task {
            // Paced so each arrival is read, not skimmed. Under Reduce Motion
            // the pace stays — it is reading time, not animation.
            try? await Task.sleep(for: .milliseconds(1_100))
            demo.advance()
            try? await Task.sleep(for: .milliseconds(900))
            demo.advance()
        }
    }

    private var arrival: AnyTransition {
        reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity)
    }

    private var avatar: some View {
        RoomAvatar(
            roomId: "demo.atlas", initial: "✳", avatarURI: nil, describesAgent: true,
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
                .accessibilityLabel("Atlas is typing")
        }
    }

    private var message: some View {
        HStack(alignment: .top, spacing: 10) {
            avatar
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(FirstRunDemo.agentName).nameFace()
                    Text("Agent")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(Theme.accentSoft, in: Capsule())
                }
                Text(FirstRunDemo.greeting)
                    .font(Theme.body)
                    .padding(12)
                    .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    /// The permission request Atlas sends, as the core would hand it to a
    /// card. Built here because nothing in the demo comes from a room.
    static var card: CustomEventView {
        .rendered(
            fields: [
                CustomEventField(label: "File", value: "docs/release-notes.md"),
                CustomEventField(label: "Change", value: "Tidy headings, add today's fixes"),
            ],
            reasoning: nil, newerVersion: false,
            decision: CustomEventDecision(
                prompt: FirstRunDemo.request,
                options: [
                    CustomEventDecisionOption(label: "Approve", id: "approve"),
                    CustomEventDecisionOption(label: "Reject", id: "reject"),
                ],
                subject: "demo-permission"),
            link: nil)
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
