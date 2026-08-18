import SupermessageFFI

/// Proof that the Swift-5 island is linked, and somewhere for the Kit's first
/// symbol to live.
///
/// Reads a type out of the generated bindings on purpose: a constant of the
/// Kit's own would still compile with the dependency removed, and would prove
/// nothing about the structure this file exists to pin.
public let linkedCoreVersion: String = String(describing: ConnectionState.self)
