//! Linking this Matrix account to the person's Super Jackfruit account.
//!
//! A person approves a gate from chat only when the suite's account service
//! (Accounts) knows which person a Matrix sender is. Accounts lets a person
//! link their own Matrix id with two proofs: their own signed-in Accounts
//! session, and an OpenID token their homeserver mints for this account
//! (`POST /_matrix/client/v3/user/{userId}/openid/request_token`), which
//! Accounts hands back to that homeserver to learn whose it is. No suite
//! service is in the path, so nothing here talks to one.
//!
//! The core mints the token and builds the page address; the host only opens
//! that address in a web authentication session and hands back the callback.
//! **The token rides in the URL fragment**, which a browser never sends to a
//! server, and it is never logged, persisted, or put in a notification or an
//! error: [`LinkToken`]'s `Debug` prints no token, and a failure from the
//! homeserver is reported without its body.
//!
//! **Standalone.** With no Accounts address for this account's homeserver —
//! neither a known one nor one the person set — there is nothing to link to,
//! and [`accounts_origin`] says so; the host then shows no row at all.

use matrix_sdk::ruma::api::client::account::request_openid_token;
use matrix_sdk::ruma::UserId;
use matrix_sdk::Client;
use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use url::Url;

use crate::error::{CoreError, CoreResult};

/// The page on Accounts that takes the token.
pub const LINK_PATH: &str = "/link/matrix";
/// Where the page returns when it was opened by this app (`app=1`). The host's
/// web authentication session listens for this scheme.
pub const CALLBACK_SCHEME: &str = "supermessage";
const CALLBACK_HOST: &str = "linked";

/// The suite's own homeservers and the Accounts each one's users link to.
/// Any other homeserver links only to an Accounts address the person sets.
const KNOWN: &[(&str, &str)] = &[
    ("id.agentpod.dev", "https://accounts.superjackfruit.com"),
    (
        "staging-id.agentpod.dev",
        "https://staging-accounts.superjackfruit.com",
    ),
];

/// An OpenID token from this account's homeserver. Never printed.
#[derive(Clone, PartialEq, Eq)]
pub struct LinkToken {
    pub token: String,
    /// The homeserver's own name for itself (`matrix_server_name`).
    pub server_name: String,
    pub expires_in_secs: u64,
}

impl std::fmt::Debug for LinkToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LinkToken")
            .field("token", &"<redacted>")
            .field("server_name", &self.server_name)
            .field("expires_in_secs", &self.expires_in_secs)
            .finish()
    }
}

/// The Accounts origin this account links to, or `None` when there is none.
///
/// `override_url` is the person's own setting and wins when it is a usable
/// origin (https, or http to this device itself); a blank one is no setting.
/// Otherwise the account's homeserver must be one of the suite's own.
pub fn accounts_origin(user_id: &str, override_url: Option<&str>) -> Option<String> {
    if let Some(raw) = override_url.map(str::trim).filter(|s| !s.is_empty()) {
        return origin_of(raw);
    }
    let server = UserId::parse(user_id)
        .ok()?
        .server_name()
        .as_str()
        .to_owned();
    KNOWN
        .iter()
        .find(|(name, _)| *name == server)
        .map(|(_, url)| (*url).to_owned())
}

fn origin_of(raw: &str) -> Option<String> {
    let url = Url::parse(raw).ok()?;
    let local = matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]"));
    let usable = url.scheme() == "https" || (url.scheme() == "http" && local);
    if !usable || url.host_str().is_none() || !url.username().is_empty() || url.password().is_some()
    {
        return None;
    }
    Some(url.origin().ascii_serialization())
}

/// Everything but unreserved characters, so a token or server name cannot
/// end the fragment's `key=value` pairs early.
const FRAGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

/// The page address: `<origin>/link/matrix#t=<token>&s=<server name>&app=1`.
pub fn link_url(origin: &str, token: &LinkToken) -> String {
    format!(
        "{origin}{LINK_PATH}#t={}&s={}&app=1",
        utf8_percent_encode(&token.token, FRAGMENT),
        utf8_percent_encode(&token.server_name, FRAGMENT),
    )
}

/// The Matrix id the page reports linked, from its `supermessage://linked?mxid=…`
/// callback; `None` for anything else.
pub fn linked_mxid(callback: &str) -> Option<String> {
    let url = Url::parse(callback).ok()?;
    if url.scheme() != CALLBACK_SCHEME || url.host_str() != Some(CALLBACK_HOST) {
        return None;
    }
    let mxid = url.query_pairs().find(|(k, _)| k == "mxid")?.1.into_owned();
    UserId::parse(&mxid).ok().map(|id| id.to_string())
}

/// Asks this account's homeserver for an OpenID token for this account.
///
/// The homeserver issues one only to the account's own access token, which is
/// the whole proof. The error carries no response body: nothing the
/// homeserver answered is echoed anywhere.
pub async fn request_link_token(client: &Client) -> CoreResult<LinkToken> {
    let user_id = client.user_id().ok_or(CoreError::NotReady)?.to_owned();
    let response = client
        .send(request_openid_token::v3::Request::new(user_id))
        .await
        .map_err(|e| {
            tracing::warn!(
                status = e.as_client_api_error().map(|c| c.status_code.as_u16()),
                "the homeserver did not issue a link token"
            );
            CoreError::Refused(
                "Your homeserver didn't issue a link token. Try again in a minute.".into(),
            )
        })?;
    Ok(LinkToken {
        token: response.access_token,
        server_name: response.matrix_server_name.to_string(),
        expires_in_secs: response.expires_in.as_secs(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use matrix_sdk::test_utils::mocks::MatrixMockServer;
    use std::sync::{Arc, Mutex};
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::Layer;
    use wiremock::matchers::{method, path_regex};
    use wiremock::{Mock, ResponseTemplate};

    const TOKEN: &str = "s3cr3t+token/with=odd&chars#1";

    fn token() -> LinkToken {
        LinkToken {
            token: TOKEN.into(),
            server_name: "id.agentpod.dev".into(),
            expires_in_secs: 300,
        }
    }

    #[test]
    fn the_suites_homeservers_link_to_their_own_accounts_and_others_to_nothing() {
        assert_eq!(
            accounts_origin("@ana:id.agentpod.dev", None).as_deref(),
            Some("https://accounts.superjackfruit.com")
        );
        assert_eq!(
            accounts_origin("@ana:staging-id.agentpod.dev", None).as_deref(),
            Some("https://staging-accounts.superjackfruit.com")
        );
        // A homeserver that merely ends like the suite's is not the suite's.
        assert_eq!(
            accounts_origin("@ana:evil-id.agentpod.dev.example", None),
            None
        );
        assert_eq!(accounts_origin("@ana:matrix.org", None), None);
        assert_eq!(accounts_origin("not an mxid", None), None);
        // A blank setting is no setting.
        assert_eq!(
            accounts_origin("@ana:id.agentpod.dev", Some("  ")).as_deref(),
            Some("https://accounts.superjackfruit.com")
        );
    }

    #[test]
    fn the_persons_own_accounts_address_wins_when_it_is_usable() {
        assert_eq!(
            accounts_origin(
                "@ana:matrix.org",
                Some("https://accounts.example/some/path?x=1")
            )
            .as_deref(),
            Some("https://accounts.example")
        );
        assert_eq!(
            accounts_origin("@ana:matrix.org", Some("http://localhost:4400")).as_deref(),
            Some("http://localhost:4400")
        );
        // Plain http to another machine, credentials in the URL, or not a URL: refused.
        for bad in [
            "http://accounts.example",
            "https://u:p@accounts.example",
            "accounts.example",
            "javascript:alert(1)",
        ] {
            assert_eq!(
                accounts_origin("@ana:id.agentpod.dev", Some(bad)),
                None,
                "{bad}"
            );
        }
    }

    #[test]
    fn the_token_rides_in_the_fragment_escaped_never_in_the_query() {
        let url = link_url("https://accounts.superjackfruit.com", &token());
        let parsed = Url::parse(&url).unwrap();
        assert_eq!(parsed.path(), LINK_PATH);
        assert_eq!(parsed.query(), None);
        let fragment = parsed.fragment().unwrap();
        let pairs: Vec<(String, String)> = url::form_urlencoded::parse(fragment.as_bytes())
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        assert_eq!(
            pairs,
            vec![
                ("t".into(), TOKEN.into()),
                ("s".into(), "id.agentpod.dev".into()),
                ("app".into(), "1".into())
            ]
        );
    }

    #[test]
    fn only_the_apps_own_callback_names_a_linked_id() {
        assert_eq!(
            linked_mxid("supermessage://linked?mxid=%40ana%3Aid.agentpod.dev").as_deref(),
            Some("@ana:id.agentpod.dev")
        );
        assert_eq!(linked_mxid("supermessage://linked"), None);
        assert_eq!(linked_mxid("supermessage://linked?mxid=nobody"), None);
        assert_eq!(
            linked_mxid("https://linked?mxid=%40ana%3Aid.agentpod.dev"),
            None
        );
        assert_eq!(
            linked_mxid("supermessage://other?mxid=%40ana%3Aid.agentpod.dev"),
            None
        );
    }

    #[test]
    fn a_link_tokens_debug_output_carries_no_token() {
        let shown = format!("{:?}", token());
        assert!(!shown.contains(TOKEN), "{shown}");
        assert!(shown.contains("id.agentpod.dev"));
    }

    /// A tracing layer that keeps every line, so a test can grep what was logged.
    #[derive(Clone, Default)]
    struct Captured(Arc<Mutex<Vec<u8>>>);
    impl std::io::Write for Captured {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    #[tokio::test]
    async fn asks_the_homeserver_for_this_accounts_own_token_and_never_logs_it() {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        let user = client.user_id().unwrap().to_string();
        Mock::given(method("POST"))
            .and(path_regex(
                r"^/_matrix/client/v3/user/[^/]+/openid/request_token$",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": TOKEN,
                "token_type": "Bearer",
                "matrix_server_name": "id.agentpod.dev",
                "expires_in": 300,
            })))
            .expect(1)
            .mount(server.server())
            .await;

        let logs = Captured::default();
        let writer = logs.clone();
        let subscriber = tracing_subscriber::registry().with(
            tracing_subscriber::fmt::layer()
                .with_writer(move || writer.clone())
                .with_ansi(false)
                .with_filter(tracing_subscriber::filter::LevelFilter::TRACE),
        );
        let _guard = tracing::subscriber::set_default(subscriber);

        let got = request_link_token(&client).await.unwrap();
        assert_eq!(got, token());
        let url = link_url("https://accounts.superjackfruit.com", &got);
        tracing::info!(?got, "built a link url");

        // Asked for this account's own token, with this account's own access token.
        let requests = server.server().received_requests().await.unwrap();
        let asked = requests
            .iter()
            .find(|r| r.url.path().ends_with("/openid/request_token"))
            .expect("the token was asked for");
        let segment = asked.url.path_segments().unwrap().nth(4).unwrap();
        assert_eq!(
            percent_encoding::percent_decode_str(segment)
                .decode_utf8()
                .unwrap(),
            user
        );
        assert!(asked
            .headers
            .get("authorization")
            .unwrap()
            .to_str()
            .unwrap()
            .starts_with("Bearer "));

        let text = String::from_utf8(logs.0.lock().unwrap().clone()).unwrap();
        assert!(
            !text.is_empty(),
            "the capture saw nothing, so it proves nothing"
        );
        assert!(!text.contains(TOKEN), "the token reached the log: {text}");
        assert!(!text.contains(&url), "the link url reached the log");
    }

    #[tokio::test]
    async fn a_refusal_from_the_homeserver_is_reported_without_its_body() {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(403).set_body_json(serde_json::json!({
                "errcode": "M_FORBIDDEN", "error": "echo-me-not",
            })))
            .mount(server.server())
            .await;
        let err = request_link_token(&client).await.unwrap_err();
        assert!(
            matches!(&err, CoreError::Refused(m) if !m.contains("echo-me-not")),
            "{err:?}"
        );
    }
}
