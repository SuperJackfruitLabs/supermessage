fn main() {
    // Calls link LiveKit's prebuilt libwebrtc. On macOS its video codec factories call methods
    // that libwebrtc adds in Objective-C categories, and a static library's category-only objects
    // are linked only with -ObjC. webrtc-sys asks for -ObjC with `cargo:rustc-link-arg`, which
    // Cargo applies to webrtc-sys's own targets, never to this app, so the app passes it. Without
    // it v0.1.4 linked none of those methods and quit when Call was pressed.
    // scripts/check-macos-objc.sh checks the built binary in CI.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        println!("cargo:rustc-link-arg=-ObjC");
    }
    tauri_build::build()
}
