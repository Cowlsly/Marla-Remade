//! Evaluating MapLibre style expressions.
//!
//! Enough of the spec to render `style/basemap.json`, and deliberately no more. The
//! operators here are exactly the ones that file uses in `fill` and `line` layers, counted
//! from the file itself rather than guessed:
//!
//! | operator | uses | | operator | uses |
//! |---|---|---|---|---|
//! | `get` | 90 | | `literal` | 22 |
//! | `zoom` | 73 | | `case` | 16 |
//! | `interpolate` | 67 | | `coalesce` | 12 |
//! | `exponential` | 54 | | `step` | 5 |
//! | `in` | 37 | | `match` | 3 |
//! | `linear` | 13 | | comparisons | 65 |
//!
//! # Why this exists
//!
//! The previous version of this module was a hand-written table of constants approximating
//! the authored style. It could not converge: every fix revealed another mismatch, because
//! the real values — 34 `landuse` kinds, opacity ramps, per-kind colour cases — are already
//! written down in a file that MapLibre renders correctly. Transcribing a 71-layer style by
//! eye is not a strategy. Evaluating it is.
//!
//! # What is deliberately missing
//!
//! `concat`, `to-string` and the other string operators, because they appear only in
//! `text-field` on `symbol` layers, and labels are a later phase. An unknown operator is an
//! error rather than a silent default, so if that changes it will say so.

use serde_json::Value as Json;
use std::collections::HashMap;

/// A value flowing through an expression.
#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Number(f64),
    String(String),
    /// A `["literal", [...]]` array, used as the haystack of `in`.
    List(Vec<Value>),
}

impl Value {
    pub fn as_number(&self) -> Option<f64> {
        match self {
            Value::Number(n) => Some(*n),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::String(s) => Some(s.as_str()),
            _ => None,
        }
    }

    /// Truthiness, as the style spec defines it for `case` and the boolean operators.
    pub fn truthy(&self) -> bool {
        match self {
            Value::Null => false,
            Value::Bool(b) => *b,
            Value::Number(n) => *n != 0.0 && !n.is_nan(),
            Value::String(s) => !s.is_empty(),
            Value::List(l) => !l.is_empty(),
        }
    }

    fn from_json(json: &Json) -> Value {
        match json {
            Json::Null => Value::Null,
            Json::Bool(b) => Value::Bool(*b),
            Json::Number(n) => Value::Number(n.as_f64().unwrap_or(0.0)),
            Json::String(s) => Value::String(s.clone()),
            Json::Array(items) => Value::List(items.iter().map(Value::from_json).collect()),
            // An object has no expression meaning here; treat it as absent rather than
            // inventing a representation for it.
            Json::Object(_) => Value::Null,
        }
    }
}

/// What an expression is evaluated against: one feature, at one zoom.
///
/// `zoom` is separate from the properties because it is the camera's, not the feature's, and
/// it is what every `interpolate` in the style keys on.
pub struct Context<'a> {
    pub zoom: f64,
    pub properties: &'a HashMap<String, Value>,
    /// `Point`, `LineString` or `Polygon`, for the legacy `["==", "$type", ...]` filter.
    pub geometry_type: &'a str,
}

impl<'a> Context<'a> {
    fn get(&self, key: &str) -> Value {
        match key {
            // The legacy filter spelling; `geometry-type` is the expression spelling.
            "$type" => Value::String(self.geometry_type.to_string()),
            _ => self.properties.get(key).cloned().unwrap_or(Value::Null),
        }
    }
}

pub type Result<T> = std::result::Result<T, String>;

/// Evaluate `expr` in `context`.
///
/// A plain JSON scalar evaluates to itself, so a paint property that is simply `"#80deea"`
/// or `0.5` needs no special case at the call site.
pub fn eval(expr: &Json, context: &Context) -> Result<Value> {
    let items = match expr {
        Json::Array(items) => items,
        // Not an expression: a literal value.
        other => return Ok(Value::from_json(other)),
    };
    let Some(op) = items.first().and_then(|v| v.as_str()) else {
        // An array that does not start with an operator is a plain array value, which is
        // what the inner array of `["literal", [...]]` looks like once unwrapped.
        return Ok(Value::from_json(expr));
    };
    let args = &items[1..];

    match op {
        "literal" => {
            let arg = args.first().ok_or("literal needs an argument")?;
            Ok(Value::from_json(arg))
        }
        "get" => {
            let key = eval(args.first().ok_or("get needs a key")?, context)?;
            let key = key.as_str().ok_or("get's key must be a string")?;
            Ok(context.get(key))
        }
        "has" => {
            let key = eval(args.first().ok_or("has needs a key")?, context)?;
            let key = key.as_str().ok_or("has's key must be a string")?;
            Ok(Value::Bool(context.get(key) != Value::Null))
        }
        "!has" => {
            let key = eval(args.first().ok_or("!has needs a key")?, context)?;
            let key = key.as_str().ok_or("!has's key must be a string")?;
            Ok(Value::Bool(context.get(key) == Value::Null))
        }
        "zoom" => Ok(Value::Number(context.zoom)),
        "geometry-type" => Ok(Value::String(context.geometry_type.to_string())),

        "all" => {
            for arg in args {
                if !eval(arg, context)?.truthy() {
                    return Ok(Value::Bool(false));
                }
            }
            Ok(Value::Bool(true))
        }
        "any" => {
            for arg in args {
                if eval(arg, context)?.truthy() {
                    return Ok(Value::Bool(true));
                }
            }
            Ok(Value::Bool(false))
        }
        "!" => {
            let v = eval(args.first().ok_or("! needs an argument")?, context)?;
            Ok(Value::Bool(!v.truthy()))
        }

        // `in` has two shapes in this style: the legacy filter `["in", key, v1, v2, ...]`
        // where the key is a bare property name, and the expression
        // `["in", needle, ["literal", [...]]]`. They are told apart by the second argument
        // being a haystack rather than a value.
        "in" | "!in" => {
            let negate = op == "!in";
            let first = args.first().ok_or("in needs a needle")?;
            let rest = &args[1..];

            let (needle, haystack) = if rest.len() == 1 {
                let candidate = eval(&rest[0], context)?;
                if let Value::List(items) = candidate {
                    (eval(first, context)?, items)
                } else {
                    // A single non-list argument: legacy form with one candidate value.
                    (legacy_key(first, context)?, vec![candidate])
                }
            } else {
                let mut haystack = Vec::with_capacity(rest.len());
                for arg in rest {
                    haystack.push(eval(arg, context)?);
                }
                (legacy_key(first, context)?, haystack)
            };
            let found = haystack.contains(&needle);
            Ok(Value::Bool(found != negate))
        }

        "==" | "!=" | "<" | "<=" | ">" | ">=" => {
            let lhs = legacy_key(args.first().ok_or("a comparison needs two sides")?, context)?;
            let rhs = eval(args.get(1).ok_or("a comparison needs two sides")?, context)?;
            compare(op, &lhs, &rhs)
        }

        "coalesce" => {
            for arg in args {
                let v = eval(arg, context)?;
                if v != Value::Null {
                    return Ok(v);
                }
            }
            Ok(Value::Null)
        }

        "case" => {
            // Pairs of condition/output, then a final fallback.
            let mut i = 0;
            while i + 1 < args.len() {
                if eval(&args[i], context)?.truthy() {
                    return eval(&args[i + 1], context);
                }
                i += 2;
            }
            eval(args.last().ok_or("case needs a fallback")?, context)
        }

        "match" => {
            let subject = eval(args.first().ok_or("match needs a subject")?, context)?;
            let mut i = 1;
            while i + 1 < args.len() {
                // Labels are **literals**, not expressions — the spec requires it. Evaluating
                // them would read `["a","b"]` as a call to an operator named `a`.
                let label = Value::from_json(&args[i]);
                // A label may be a single value or a list of alternatives.
                let matched = match &label {
                    Value::List(alternatives) => alternatives.contains(&subject),
                    single => *single == subject,
                };
                if matched {
                    return eval(&args[i + 1], context);
                }
                i += 2;
            }
            eval(args.last().ok_or("match needs a fallback")?, context)
        }

        "step" => {
            // ["step", input, base, stop1, out1, stop2, out2, ...]
            let input = eval(args.first().ok_or("step needs an input")?, context)?
                .as_number()
                .ok_or("step's input must be a number")?;
            let mut chosen = args.get(1).ok_or("step needs a base output")?;
            let mut i = 2;
            while i + 1 < args.len() {
                let stop = eval(&args[i], context)?
                    .as_number()
                    .ok_or("a step stop must be a number")?;
                if input < stop {
                    break;
                }
                chosen = &args[i + 1];
                i += 2;
            }
            eval(chosen, context)
        }

        "interpolate" => interpolate(args, context),

        other => Err(format!("unsupported style expression operator `{other}`")),
    }
}

/// The left-hand side of a legacy filter is a bare property name, not a `get`.
///
/// `["==", "kind", "park"]` means the *property* `kind`, while the expression form is
/// `["==", ["get", "kind"], "park"]`. Both appear in this style, so a bare string on the
/// left is read as a property lookup and anything else is evaluated normally.
fn legacy_key(arg: &Json, context: &Context) -> Result<Value> {
    if let Json::String(key) = arg {
        return Ok(context.get(key));
    }
    eval(arg, context)
}

fn compare(op: &str, lhs: &Value, rhs: &Value) -> Result<Value> {
    let result = match op {
        "==" => lhs == rhs,
        "!=" => lhs != rhs,
        _ => {
            // Ordering only makes sense for numbers here; the style compares zoom and
            // numeric properties.
            let (a, b) = match (lhs.as_number(), rhs.as_number()) {
                (Some(a), Some(b)) => (a, b),
                // A missing property is not orderable, and the spec's answer is false rather
                // than an error — a feature without the property simply does not match.
                _ => return Ok(Value::Bool(false)),
            };
            match op {
                "<" => a < b,
                "<=" => a <= b,
                ">" => a > b,
                ">=" => a >= b,
                _ => unreachable!("checked by the caller"),
            }
        }
    };
    Ok(Value::Bool(result))
}

/// `["interpolate", ["linear"] | ["exponential", base], input, stop, out, ...]`
fn interpolate(args: &[Json], context: &Context) -> Result<Value> {
    let kind = args.first().ok_or("interpolate needs an interpolation type")?;
    let base = match kind {
        Json::Array(items) => match items.first().and_then(|v| v.as_str()) {
            Some("linear") => 1.0,
            Some("exponential") => items
                .get(1)
                .and_then(|v| v.as_f64())
                .ok_or("exponential interpolation needs a base")?,
            // `cubic-bezier` is not used by this style; erroring is better than silently
            // interpolating it as linear and being subtly wrong everywhere.
            other => return Err(format!("unsupported interpolation `{other:?}`")),
        },
        other => return Err(format!("unsupported interpolation `{other:?}`")),
    };

    let input = eval(args.get(1).ok_or("interpolate needs an input")?, context)?
        .as_number()
        .ok_or("interpolate's input must be a number")?;

    let stops = &args[2..];
    if stops.len() < 2 {
        return Err("interpolate needs at least one stop".into());
    }

    let stop_at = |i: usize| -> Result<f64> {
        eval(&stops[i], context)?.as_number().ok_or_else(|| "a stop must be a number".into())
    };

    // Below the first stop and above the last, the spec clamps.
    if input <= stop_at(0)? {
        return eval(&stops[1], context);
    }
    let last = stops.len() - 2;
    if input >= stop_at(last)? {
        return eval(&stops[last + 1], context);
    }

    let mut i = 0;
    while i + 3 < stops.len() {
        let lower = stop_at(i)?;
        let upper = stop_at(i + 2)?;
        if input >= lower && input <= upper {
            let span = upper - lower;
            let t = if span <= 0.0 {
                0.0
            } else if base == 1.0 {
                (input - lower) / span
            } else {
                // The spec's exponential curve.
                (base.powf(input - lower) - 1.0) / (base.powf(span) - 1.0)
            };
            let a = eval(&stops[i + 1], context)?;
            let b = eval(&stops[i + 3], context)?;
            return mix(&a, &b, t);
        }
        i += 2;
    }
    eval(&stops[last + 1], context)
}

/// Interpolate between two outputs. Numbers blend; colours are handled by the caller, which
/// knows to parse them.
fn mix(a: &Value, b: &Value, t: f64) -> Result<Value> {
    match (a, b) {
        (Value::Number(a), Value::Number(b)) => Ok(Value::Number(a + (b - a) * t)),
        // Colour stops are strings at this level. Returning the nearer endpoint would be a
        // visible banding artifact, so the colour-typed caller interpolates instead; see
        // `paint::color_at`.
        (Value::String(_), Value::String(_)) => {
            Ok(if t < 0.5 { a.clone() } else { b.clone() })
        }
        _ => Err("cannot interpolate between these value types".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx<'a>(
        zoom: f64,
        properties: &'a HashMap<String, Value>,
        geometry_type: &'a str,
    ) -> Context<'a> {
        Context { zoom, properties, geometry_type }
    }

    fn props(pairs: &[(&str, Value)]) -> HashMap<String, Value> {
        pairs.iter().map(|(k, v)| (k.to_string(), v.clone())).collect()
    }

    fn parse(source: &str) -> Json {
        serde_json::from_str(source).expect("valid JSON")
    }

    fn eval_str(source: &str, zoom: f64, properties: &[(&str, Value)]) -> Value {
        let p = props(properties);
        eval(&parse(source), &ctx(zoom, &p, "Polygon")).expect("evaluates")
    }

    fn s(v: &str) -> Value {
        Value::String(v.to_string())
    }

    #[test]
    fn a_plain_scalar_is_its_own_value() {
        // So a paint property that is simply a colour or a number needs no special case.
        assert_eq!(eval_str(r##""#80deea""##, 0.0, &[]), s("#80deea"));
        assert_eq!(eval_str("0.5", 0.0, &[]), Value::Number(0.5));
    }

    #[test]
    fn get_reads_a_property_and_missing_is_null() {
        assert_eq!(eval_str(r#"["get","kind"]"#, 0.0, &[("kind", s("park"))]), s("park"));
        assert_eq!(eval_str(r#"["get","kind"]"#, 0.0, &[]), Value::Null);
    }

    #[test]
    fn has_and_not_has_follow_property_presence() {
        // `!has` appears 32 times in this style, mostly to exclude tunnels and bridges.
        assert_eq!(eval_str(r#"["has","is_tunnel"]"#, 0.0, &[("is_tunnel", Value::Bool(true))]), Value::Bool(true));
        assert_eq!(eval_str(r#"["has","is_tunnel"]"#, 0.0, &[]), Value::Bool(false));
        assert_eq!(eval_str(r#"["!has","is_tunnel"]"#, 0.0, &[]), Value::Bool(true));
    }

    #[test]
    fn the_legacy_filter_form_treats_a_bare_string_as_a_property() {
        // `["==","kind","park"]` means the property, not the literal string "kind". Reading
        // it as a literal makes every such filter compare "kind" to "park" and match nothing.
        assert_eq!(
            eval_str(r#"["==","kind","park"]"#, 0.0, &[("kind", s("park"))]),
            Value::Bool(true),
        );
        assert_eq!(
            eval_str(r#"["==","kind","park"]"#, 0.0, &[("kind", s("wood"))]),
            Value::Bool(false),
        );
        // And the expression form must still work.
        assert_eq!(
            eval_str(r#"["==",["get","kind"],"park"]"#, 0.0, &[("kind", s("park"))]),
            Value::Bool(true),
        );
    }

    #[test]
    fn the_dollar_type_filter_reads_the_geometry_type() {
        let p = props(&[]);
        let expr = parse(r#"["==","$type","Polygon"]"#);
        assert_eq!(eval(&expr, &ctx(0.0, &p, "Polygon")).unwrap(), Value::Bool(true));
        assert_eq!(eval(&expr, &ctx(0.0, &p, "LineString")).unwrap(), Value::Bool(false));
    }

    #[test]
    fn in_supports_both_the_legacy_and_expression_forms() {
        // Legacy: ["in", key, v1, v2, ...]
        assert_eq!(
            eval_str(r#"["in","kind","park","wood","grass"]"#, 0.0, &[("kind", s("wood"))]),
            Value::Bool(true),
        );
        assert_eq!(
            eval_str(r#"["in","kind","park","wood"]"#, 0.0, &[("kind", s("pier"))]),
            Value::Bool(false),
        );
        // Expression: ["in", needle, ["literal", [...]]]
        assert_eq!(
            eval_str(
                r#"["in",["get","kind"],["literal",["national_park","park"]]]"#,
                0.0,
                &[("kind", s("national_park"))],
            ),
            Value::Bool(true),
        );
        assert_eq!(
            eval_str(r#"["!in","kind","park"]"#, 0.0, &[("kind", s("wood"))]),
            Value::Bool(true),
        );
    }

    #[test]
    fn all_and_any_short_circuit_correctly() {
        assert_eq!(
            eval_str(
                r#"["all",["==","kind","other"],["!has","is_tunnel"]]"#,
                0.0,
                &[("kind", s("other"))],
            ),
            Value::Bool(true),
        );
        assert_eq!(
            eval_str(
                r#"["all",["==","kind","other"],["!has","is_tunnel"]]"#,
                0.0,
                &[("kind", s("other")), ("is_tunnel", Value::Bool(true))],
            ),
            Value::Bool(false),
        );
        assert_eq!(eval_str(r#"["any",["==","kind","a"],["==","kind","b"]]"#, 0.0, &[("kind", s("b"))]), Value::Bool(true));
        assert_eq!(eval_str(r#"["all"]"#, 0.0, &[]), Value::Bool(true), "an empty all is true");
        assert_eq!(eval_str(r#"["any"]"#, 0.0, &[]), Value::Bool(false), "an empty any is false");
    }

    #[test]
    fn comparisons_on_a_missing_property_are_false_rather_than_an_error() {
        // The style compares numeric properties that many features simply lack.
        assert_eq!(eval_str(r#"[">=","min_zoom",5]"#, 0.0, &[]), Value::Bool(false));
        assert_eq!(eval_str(r#"["<","min_zoom",5]"#, 0.0, &[]), Value::Bool(false));
        assert_eq!(
            eval_str(r#"[">=","min_zoom",5]"#, 0.0, &[("min_zoom", Value::Number(7.0))]),
            Value::Bool(true),
        );
    }

    #[test]
    fn case_picks_the_first_true_arm_and_falls_back() {
        let expr = r##"["case",["==","kind","park"],"#9cd3b4",["==","kind","wood"],"#a0d9a0","#e2dfda"]"##;
        assert_eq!(eval_str(expr, 0.0, &[("kind", s("park"))]), s("#9cd3b4"));
        assert_eq!(eval_str(expr, 0.0, &[("kind", s("wood"))]), s("#a0d9a0"));
        assert_eq!(eval_str(expr, 0.0, &[("kind", s("pier"))]), s("#e2dfda"), "the fallback");
    }

    #[test]
    fn match_supports_list_labels_and_a_fallback() {
        // The landcover colour expression is a `match` whose arms are single values.
        let expr = r#"["match",["get","kind"],"grassland","rgba(210,239,207,1)","barren","rgba(255,243,215,1)","rgba(196,231,210,1)"]"#;
        assert_eq!(eval_str(expr, 0.0, &[("kind", s("grassland"))]), s("rgba(210,239,207,1)"));
        assert_eq!(eval_str(expr, 0.0, &[("kind", s("barren"))]), s("rgba(255,243,215,1)"));
        assert_eq!(eval_str(expr, 0.0, &[("kind", s("wetland"))]), s("rgba(196,231,210,1)"));
        // A label that is itself a list means "any of these".
        let listed = r#"["match",["get","kind"],["a","b"],"hit","miss"]"#;
        assert_eq!(eval_str(listed, 0.0, &[("kind", s("b"))]), s("hit"));
        assert_eq!(eval_str(listed, 0.0, &[("kind", s("c"))]), s("miss"));
    }

    #[test]
    fn step_holds_each_output_until_the_next_stop() {
        // `["step", ["zoom"], base, 6, a, 10, b]`
        let expr = r#"["step",["zoom"],0,6,1,10,2]"#;
        assert_eq!(eval_str(expr, 0.0, &[]), Value::Number(0.0), "below the first stop");
        assert_eq!(eval_str(expr, 5.9, &[]), Value::Number(0.0));
        assert_eq!(eval_str(expr, 6.0, &[]), Value::Number(1.0), "inclusive at the stop");
        assert_eq!(eval_str(expr, 9.9, &[]), Value::Number(1.0));
        assert_eq!(eval_str(expr, 10.0, &[]), Value::Number(2.0));
        assert_eq!(eval_str(expr, 22.0, &[]), Value::Number(2.0));
    }

    #[test]
    fn linear_interpolation_matches_the_landcover_opacity_ramp() {
        // The real expression, and the one whose absence painted a green blanket over the
        // whole map: opacity 1 at z5 falling to 0 at z7.
        let expr = r#"["interpolate",["linear"],["zoom"],5,1,7,0]"#;
        assert_eq!(eval_str(expr, 4.0, &[]).as_number().unwrap(), 1.0, "clamped below");
        assert_eq!(eval_str(expr, 5.0, &[]).as_number().unwrap(), 1.0);
        assert!((eval_str(expr, 6.0, &[]).as_number().unwrap() - 0.5).abs() < 1e-9, "halfway");
        assert_eq!(eval_str(expr, 7.0, &[]).as_number().unwrap(), 0.0);
        assert_eq!(eval_str(expr, 15.0, &[]).as_number().unwrap(), 0.0, "clamped above");
    }

    #[test]
    fn the_landuse_opacity_ramp_keeps_it_off_at_world_zoom() {
        // 0 at z6 rising to 1 at z11. Ignoring this drew continent-sized national parks at
        // world zoom, whose tile-clipped edges looked like cuts slashed across the map.
        let expr = r#"["interpolate",["linear"],["zoom"],6,0,11,1]"#;
        assert_eq!(eval_str(expr, 1.0, &[]).as_number().unwrap(), 0.0);
        assert_eq!(eval_str(expr, 6.0, &[]).as_number().unwrap(), 0.0);
        assert!((eval_str(expr, 8.5, &[]).as_number().unwrap() - 0.5).abs() < 1e-9);
        assert_eq!(eval_str(expr, 11.0, &[]).as_number().unwrap(), 1.0);
        assert_eq!(eval_str(expr, 20.0, &[]).as_number().unwrap(), 1.0);
    }

    #[test]
    fn exponential_interpolation_matches_a_real_line_width_ramp() {
        // `roads_runway`: ["interpolate",["exponential",1.6],["zoom"],10,0,12,4,18,30]
        let expr = r#"["interpolate",["exponential",1.6],["zoom"],10,0,12,4,18,30]"#;
        assert_eq!(eval_str(expr, 9.0, &[]).as_number().unwrap(), 0.0, "clamped below");
        assert_eq!(eval_str(expr, 10.0, &[]).as_number().unwrap(), 0.0);
        assert_eq!(eval_str(expr, 12.0, &[]).as_number().unwrap(), 4.0, "exactly on a stop");
        assert_eq!(eval_str(expr, 18.0, &[]).as_number().unwrap(), 30.0);
        assert_eq!(eval_str(expr, 22.0, &[]).as_number().unwrap(), 30.0, "clamped above");

        // Between stops the curve is below the straight line, which is the whole point of an
        // exponential base above 1.
        let midpoint = eval_str(expr, 11.0, &[]).as_number().unwrap();
        assert!(midpoint > 0.0 && midpoint < 2.0, "got {midpoint}, expected under the linear 2.0");
    }

    #[test]
    fn interpolation_between_colour_stops_does_not_error() {
        // Colour ramps exist in this style; the numeric path must not be the only one.
        let expr = r##"["interpolate",["linear"],["zoom"],0,"#000000",10,"#ffffff"]"##;
        assert_eq!(eval_str(expr, 0.0, &[]), s("#000000"));
        assert_eq!(eval_str(expr, 10.0, &[]), s("#ffffff"));
        assert!(matches!(eval_str(expr, 5.0, &[]), Value::String(_)));
    }

    #[test]
    fn coalesce_takes_the_first_present_value() {
        assert_eq!(
            eval_str(r#"["coalesce",["get","name:en"],["get","name"],""]"#, 0.0, &[("name", s("Paris"))]),
            s("Paris"),
        );
        assert_eq!(
            eval_str(r#"["coalesce",["get","a"],["get","b"]]"#, 0.0, &[]),
            Value::Null,
        );
    }

    #[test]
    fn an_unknown_operator_is_an_error_rather_than_a_silent_default() {
        // Silently defaulting would render a subtly wrong map with nothing to indicate why,
        // which is the failure mode this whole module exists to end.
        let p = props(&[]);
        let expr = parse(r#"["cubic-bezier",0,0,1,1]"#);
        let error = eval(&expr, &ctx(0.0, &p, "Polygon")).expect_err("must not be silently ignored");
        assert!(error.contains("cubic-bezier"), "unhelpful message: {error}");
    }

    #[test]
    fn truthiness_follows_the_spec() {
        assert!(!Value::Null.truthy());
        assert!(!Value::Bool(false).truthy());
        assert!(Value::Bool(true).truthy());
        assert!(!Value::Number(0.0).truthy());
        assert!(Value::Number(1.0).truthy());
        assert!(!Value::String(String::new()).truthy());
        assert!(s("x").truthy());
    }
}
