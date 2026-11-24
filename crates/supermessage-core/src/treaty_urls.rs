//! Treaty's URL shapes for linking an account, run through this client's own builder and parser.
//!
//! `accounts.matrix-link` is a contract in treaty: the Accounts page this client opens
//! (`schemas/urls/accounts-matrix-link.v1.json`) and the callback that page returns through
//! (`supermessage-linked.v1.json`), each with URLs it must accept (`valid`) and refuse
//! (`invalid`). They are vendored under `vendor/treaty/` at the release `treaty.json` pins.
//!
//! - **Every page address [`link_url`] builds is one the shape accepts**: it is built from each
//!   valid case's origin, token and server name, and checked against the shape's rules.
//! - **[`linked_mxid`] reads every valid callback and refuses every invalid one.**
//!
//! The shapes' rules are checked here by [`check`], which knows only the keywords these shapes
//! use and fails on any other, and which must answer every case as treaty does
//! ([`the_checker_answers_every_case_as_treaty_does`]) before it is trusted with a built URL.

use regex::Regex;
use serde_json::{Map, Value};
use url::Url;

use crate::account_link::{link_url, linked_mxid, LinkToken};

const LINK: &str = include_str!("../../../vendor/treaty/schemas/urls/accounts-matrix-link.v1.json");
const LINK_CASES: &str =
    include_str!("../../../vendor/treaty/fixtures/urls/accounts-matrix-link.v1.cases.json");
const LINKED: &str =
    include_str!("../../../vendor/treaty/schemas/urls/supermessage-linked.v1.json");
const LINKED_CASES: &str =
    include_str!("../../../vendor/treaty/fixtures/urls/supermessage-linked.v1.cases.json");

fn json(text: &str) -> Value {
    serde_json::from_str(text).expect("vendored JSON parses")
}

/// URLs with what names them: a valid case's label, or why an invalid one is refused.
type Named = Vec<(String, String)>;

/// A cases file's `valid` URLs and its `invalid` ones.
fn cases(text: &str) -> (Named, Named) {
    let doc = json(text);
    let valid = doc["valid"]
        .as_object()
        .expect("valid is an object")
        .iter()
        .map(|(label, url)| (label.clone(), url.as_str().expect("a URL").to_owned()))
        .collect();
    let invalid = doc["invalid"]
        .as_array()
        .expect("invalid is an array")
        .iter()
        .map(|c| {
            (
                c["why"].as_str().expect("a why").to_owned(),
                c["body"].as_str().expect("a URL").to_owned(),
            )
        })
        .collect();
    (valid, invalid)
}

/// What a shape refuses about `url`; empty when it accepts it.
fn check(shape: &Value, url: &str) -> Vec<String> {
    let Ok(parsed) = Url::parse(url) else {
        return vec!["not an absolute URL".into()];
    };
    let mut errors = Vec::new();
    if !parsed.username().is_empty() || parsed.password().is_some() {
        errors.push("a URL with a user name or password".into());
    }
    let host = parsed.host_str().unwrap_or("");
    let origin = shape["origins"]
        .as_array()
        .expect("origins")
        .iter()
        .any(|o| {
            o["scheme"].as_str() == Some(parsed.scheme())
                && o.get("hosts")
                    .and_then(Value::as_array)
                    .is_none_or(|hosts| hosts.iter().any(|h| h.as_str() == Some(host)))
        });
    if !origin {
        errors.push(format!("{}://{host} is not an origin", parsed.scheme()));
    }
    if parsed.path() != shape["path"].as_str().expect("path") {
        errors.push(format!("path {:?}", parsed.path()));
    }
    for (part, raw) in [
        ("query", parsed.query().unwrap_or("")),
        ("fragment", parsed.fragment().unwrap_or("")),
    ] {
        let Some(params) = shape.get(part).and_then(Value::as_object) else {
            continue;
        };
        let pairs: Vec<(String, String)> = url::form_urlencoded::parse(raw.as_bytes())
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        for (name, p) in params {
            let values: Vec<&str> = pairs
                .iter()
                .filter(|(k, _)| k == name)
                .map(|(_, v)| v.as_str())
                .collect();
            match values.as_slice() {
                [] if p["required"] == Value::Bool(true) => {
                    errors.push(format!("{part} {name} is required"));
                }
                [] => {}
                [value] => errors.extend(
                    refusals(p["schema"].as_object().expect("a schema"), value)
                        .into_iter()
                        .map(|e| format!("{part} {name}: {e}")),
                ),
                more => errors.push(format!("{part} {name} appears {} times", more.len())),
            }
        }
    }
    errors
}

/// What a parameter's schema refuses about its decoded value.
fn refusals(schema: &Map<String, Value>, value: &str) -> Vec<String> {
    let mut errors = Vec::new();
    for (keyword, rule) in schema {
        let ok = match keyword.as_str() {
            "type" => rule == "string",
            "minLength" => value.chars().count() as u64 >= rule.as_u64().expect("a length"),
            "pattern" => Regex::new(rule.as_str().expect("a pattern"))
                .expect("the pattern compiles")
                .is_match(value),
            "const" => rule.as_str() == Some(value),
            "description" => true,
            other => {
                panic!("this check was taught no rule for {other:?}; teach it before trusting it")
            }
        };
        if !ok {
            errors.push(format!("{keyword} {rule}"));
        }
    }
    errors
}

/// A URL's fragment parameter, decoded.
fn fragment_param(url: &Url, name: &str) -> Option<String> {
    url::form_urlencoded::parse(url.fragment()?.as_bytes())
        .find(|(k, _)| k == name)
        .map(|(_, v)| v.into_owned())
}

#[test]
fn the_checker_answers_every_case_as_treaty_does() {
    for (shape, text) in [(json(LINK), LINK_CASES), (json(LINKED), LINKED_CASES)] {
        let (valid, invalid) = cases(text);
        assert!(!valid.is_empty() && !invalid.is_empty());
        for (label, url) in valid {
            assert_eq!(check(&shape, &url), Vec::<String>::new(), "{label}: {url}");
        }
        for (why, url) in invalid {
            assert!(!check(&shape, &url).is_empty(), "accepted {why}: {url}");
        }
    }
}

#[test]
fn every_page_address_link_url_builds_is_one_the_shape_accepts() {
    let shape = json(LINK);
    let (valid, _) = cases(LINK_CASES);
    let mut odd = valid.clone();
    // A token with every character that could end a fragment pair early.
    odd.push((
        "an odd token".into(),
        "https://accounts.superjackfruit.com/link/matrix#t=s3cr3t%2Btoken%2Fwith%3Dodd%26chars%231&s=id.agentpod.dev".into(),
    ));
    for (label, case) in odd {
        let url = Url::parse(&case).unwrap();
        let token = LinkToken {
            token: fragment_param(&url, "t").unwrap(),
            server_name: fragment_param(&url, "s").unwrap(),
            expires_in_secs: 300,
        };
        let built = link_url(&url.origin().ascii_serialization(), &token);
        assert_eq!(
            check(&shape, &built),
            Vec::<String>::new(),
            "{label}: {built}"
        );
        let read = Url::parse(&built).unwrap();
        assert_eq!(
            fragment_param(&read, "t"),
            Some(token.token.clone()),
            "{label}"
        );
        assert_eq!(
            fragment_param(&read, "s"),
            Some(token.server_name.clone()),
            "{label}"
        );
        assert_eq!(
            fragment_param(&read, "app").as_deref(),
            Some("1"),
            "{label}"
        );
    }
}

#[test]
fn the_case_from_this_client_is_exactly_what_link_url_builds() {
    let (valid, _) = cases(LINK_CASES);
    let (_, case) = valid
        .iter()
        .find(|(label, _)| label == "from SuperMessage")
        .expect("treaty names the case this client builds");
    let url = Url::parse(case).unwrap();
    let token = LinkToken {
        token: fragment_param(&url, "t").unwrap(),
        server_name: fragment_param(&url, "s").unwrap(),
        expires_in_secs: 300,
    };
    assert_eq!(&link_url(&url.origin().ascii_serialization(), &token), case);
}

#[test]
fn linked_mxid_reads_every_valid_callback() {
    let (valid, _) = cases(LINKED_CASES);
    for (label, case) in valid {
        let url = Url::parse(&case).unwrap();
        let mxid = url
            .query_pairs()
            .find(|(k, _)| k == "mxid")
            .map(|(_, v)| v.into_owned());
        assert_eq!(linked_mxid(&case), mxid, "{label}: {case}");
    }
}

#[test]
fn linked_mxid_refuses_every_invalid_callback() {
    let (_, invalid) = cases(LINKED_CASES);
    for (why, case) in invalid {
        assert_eq!(linked_mxid(&case), None, "read {why}: {case}");
    }
}
