import SwiftUI

/// The ground under anything that floats over the timeline: the composer's
/// capsule, the `@` picker, the acknowledgement dock.
///
/// **Liquid Glass where the system has it, the palette where it does not.**
/// `glassEffect` is iOS 26 API and this project builds against the iOS 18.5
/// SDK (Xcode 16.4), where the symbol does not exist at all — so an
/// `#available` check alone would not compile. The `compiler(>=6.2)` guard
/// is what keeps the call out of a build whose SDK cannot see it; the
/// `#available` inside it is what keeps an iOS 18 device off it at run time.
///
/// The fallback is drawn from the palette (principle 5): `surfaceRaised` with
/// the hairline `border`, never `.bar` or a stock material, whose greys are
/// the system's rather than ours.
struct FloatingSurface<S: Shape>: ViewModifier {
    let shape: S

    func body(content: Content) -> some View {
        #if compiler(>=6.2)
            if #available(iOS 26, *) {
                content.glassEffect(.regular, in: shape)
            } else {
                palette(content)
            }
        #else
            palette(content)
        #endif
    }

    private func palette(_ content: Content) -> some View {
        content
            .background(Theme.surfaceRaised, in: shape)
            .overlay(shape.stroke(Theme.border, lineWidth: 1))
            .shadow(color: Theme.scrim.opacity(0.10), radius: 10, y: 3)
    }
}

extension View {
    func floatingSurface(cornerRadius: CGFloat) -> some View {
        modifier(FloatingSurface(shape: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)))
    }

    func floatingCapsule() -> some View {
        modifier(FloatingSurface(shape: Capsule(style: .continuous)))
    }
}

/// "Agent", on anyone who is one (principle 3: agents are labelled).
///
/// Named for where it started rather than claimed as *the* badge: the
/// timeline's agent card may grow its own, and two files each declaring an
/// `AgentBadge` in one module would not compile.
struct AgentTag: View {
    var body: some View {
        Text("Agent")
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .foregroundStyle(Theme.accent)
            .background(Theme.accentSoft, in: Capsule())
            .accessibilityLabel("Agent")
    }
}
