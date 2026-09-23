"""
KARM CRED Flask API.

Security & privacy notes (see README.md "Security" section):
  - CORS is restricted to the ALLOWED_ORIGINS env var (default: local
    dashboard origins) instead of allowing every origin.
  - Debug mode is OFF unless FLASK_DEBUG=1. Flask's debug console is a
    remote-code-execution surface - never expose it on a demo/network.
  - CSV data is loaded once into an in-memory SQLite database and read with
    parameterized queries; /api/user/<id> validates the row and every field
    before touching the model (422 on malformed/missing data).
  - Per-source consent: GET /api/user/<id>?consent=upi,utility,platform.
    Features of any source NOT granted are nulled before the model sees
    them - the model literally never receives non-consented data. Omitting
    the parameter (web dashboard default) grants all sources.
"""

import os
import sqlite3

import numpy as np
import pandas as pd
from flask import Flask, jsonify, render_template, request
from flask_cors import CORS

from ml.data_utils import (
    COMPLETENESS_PRIMARIES,
    COHORT_COLS,
    MODEL_FEATURES,
    SOURCE_OF_FEATURE,
    load_dataset,
)
from ml.explainability import FEATURE_LABELS, _clamp, items_from_shap
from ml.fairness_check import (
    HEALTHY_FLOOR_MIN,
    HEALTHY_TREND_MIN,
    HEALTHY_UTILITY_MIN,
    MAX_ACCEPTABLE_GAP,
)
from ml.predict import (
    features_frame,
    get_prediction_and_explanation,
    score_dataframe,
    shap_matrix,
)

app = Flask(__name__)

ALLOWED_ORIGINS = [
    o.strip() for o in os.environ.get(
        "ALLOWED_ORIGINS",
        "http://localhost:5000,http://127.0.0.1:5000",
    ).split(",") if o.strip()
]
CORS(app, origins=ALLOWED_ORIGINS)

# ---------------------------------------------------------------------------
# Storage: in-memory SQLite (parameterized queries; CSV loaded once at boot).
# A pandas copy (_df) backs the cached population views (leaderboard/fairness)
# so predictions are computed once, never per request.
# ---------------------------------------------------------------------------
_db = sqlite3.connect(":memory:", check_same_thread=False)
_df = load_dataset()
_df.to_sql("users", _db, index=False)

_CACHE = {}  # lazily built population caches (valid until process restart)


def _cached_scores():
    """Whole-dataset scores, computed once (one vectorized predict call)."""
    if "scores" not in _CACHE:
        _CACHE["scores"] = score_dataframe(_df)
    return _CACHE["scores"]


def _cached_features():
    """Imputed model-input frame for the whole dataset (used for masks)."""
    if "features" not in _CACHE:
        _CACHE["features"] = features_frame(_df)
    return _CACHE["features"]

# Fields the generator guarantees; if a row is malformed/missing one of
# these we refuse to score rather than silently guessing.
REQUIRED_FIELDS = [
    "monthly_avg_income",
    "cohort_adjusted_volatility",
    "income_trend_slope",
    "income_floor_ratio",
    "earning_gap_irregularity",
    "platform_tenure_months",
    "avg_savings_ratio",
]

GRANTABLE_SOURCES = {"upi", "utility", "platform"}


def _jsonable(value):
    """pandas NaN/NaT -> None so the response is strict JSON (no NaN token)."""
    if value is None:
        return None
    try:
        if pd.isna(value):
            return None
    except (TypeError, ValueError):
        pass
    if hasattr(value, "item"):  # numpy scalar
        value = value.item()
    return value


@app.route("/")
def index():
    return render_template("index.html")


# ---------------------------------------------------------------------------
# Shared single-user helpers (used by /api/user, /api/simulate, /api/compare)
# ---------------------------------------------------------------------------
def _lookup_record(user_id):
    """Fetch + validate a CSV row.

    Returns (record, search_id, error); error is a ready-to-return
    (json_response, status_code) tuple, or None when the row is usable.
    """
    search_id = str(user_id).strip().upper()

    row = pd.read_sql_query(
        "SELECT * FROM users WHERE upper(trim(user_id)) = ? LIMIT 1",
        _db, params=(search_id,))
    if row.empty:
        sample = pd.read_sql_query(
            "SELECT user_id FROM users LIMIT 3", _db)["user_id"].tolist()
        return None, search_id, (jsonify({
            "error": f"User ID '{user_id}' not found.",
            "sample_ids_in_csv": sample,
        }), 404)

    record = row.iloc[0]

    # --- validate required fields before scoring --------------------------
    missing = [f for f in REQUIRED_FIELDS if f not in record.index
               or record[f] is None or pd.isna(record[f])]
    if missing:
        return None, search_id, (jsonify({
            "error": "Malformed profile: required fields missing or invalid.",
            "user_id": search_id,
            "missing_fields": missing,
        }), 422)
    return record, search_id, None


def _parse_consent(consent_arg):
    """'?consent=upi,utility' (or a list) -> granted set. None -> all."""
    if consent_arg is None:
        return set(GRANTABLE_SOURCES)  # web dashboard default: all granted
    if isinstance(consent_arg, (list, tuple)):
        parts = [str(s) for s in consent_arg]
    else:
        parts = str(consent_arg).split(",")
    return {s.strip().lower() for s in parts if s.strip()} & GRANTABLE_SOURCES


def _fix_completeness(payload):
    """Effective completeness = min(originally present, currently provided).

    Reflects BOTH sources missing from the record AND sources the user just
    declined to share (min of the two).
    """
    originally = payload.get("data_completeness")
    present = sum(1 for f in COMPLETENESS_PRIMARIES
                  if payload.get(f) is not None)
    observed = present / len(COMPLETENESS_PRIMARIES)
    payload["data_completeness"] = (min(originally, observed)
                                    if originally is not None else observed)


def _build_payload(record, granted):
    """Model payload from a CSV row + granted sources.

    Consent gate: features of any non-consented source are nulled BEFORE
    completeness/imputation - the model literally never receives them.
    """
    payload = {f: _jsonable(record[f]) if f in record.index else None
               for f in MODEL_FEATURES}

    for feature, source in SOURCE_OF_FEATURE.items():
        if source not in granted:
            payload[feature] = None  # nulled, then cohort-imputed downstream

    _fix_completeness(payload)

    cohort = {c: _jsonable(record[c]) for c in COHORT_COLS
              if c in record.index}
    for c in COHORT_COLS:
        payload[c] = cohort.get(c)
    return payload, cohort


def _radar_values(payload):
    """Radar axes: Income Floor / UPI / Utility / Rating / Savings."""
    def axis(value, scale, cap=100.0, default=50.0):
        if value is None:
            return default
        try:
            if pd.isna(value):
                return default
        except (TypeError, ValueError):
            pass
        return round(min(float(value) * scale, cap), 1)

    return [
        axis(payload["income_floor_ratio"], 100.0),
        axis(payload["upi_monthly_txns"], 100.0 / 2.5),
        axis(payload["utility_on_time_ratio"], 100.0),
        axis(payload["avg_platform_rating"], 20.0),
        axis(payload["avg_savings_ratio"], 200.0),
    ]


def _result_payload(payload, cohort, granted, ml_result, user_id=None):
    """Full response dict for one scored worker (shared by all endpoints)."""
    out = {
        "cohort": cohort,
        "consent": sorted(granted),
        "features": payload,
        "predicted_trust_score": ml_result["predicted_trust_score"],
        "summary": ml_result["summary"],
        "reasons": ml_result["reasons"],
        "counterfactuals": ml_result["counterfactuals"],
        "radar_values": _radar_values(payload),
    }
    if user_id is not None:
        return {"user_id": user_id, **out}
    return out


@app.route("/api/user/<user_id>", methods=["GET"])
def get_user_data(user_id):
    record, search_id, err = _lookup_record(user_id)
    if err is not None:
        return err

    granted = _parse_consent(request.args.get("consent"))
    payload, cohort = _build_payload(record, granted)
    ml_result = get_prediction_and_explanation(payload)
    return jsonify(_result_payload(payload, cohort, granted, ml_result,
                                   user_id=search_id))


# ---------------------------------------------------------------------------
# Population views - predictions/SHAP are computed ONCE and cached; no route
# below recomputes the whole dataset on a request.
# ---------------------------------------------------------------------------
LB_HIGHLIGHT_KEYS = [
    "monthly_avg_income",
    "income_floor_ratio",
    "income_trend_slope",
    "earning_gap_irregularity",
    "cohort_adjusted_volatility",
    "utility_on_time_ratio",
    "avg_platform_rating",
]


def _lb_entry(scores, idx, reason_items):
    """One leaderboard card: score + top-2 plain-language reasons + stats."""
    row = _df.loc[idx]
    top = reason_items[:2]
    top_reason = "; ".join(
        f"{it['feature']} {'+' if it['impact_points'] >= 0 else ''}"
        f"{it['impact_points']} pts"
        for it in top
    )
    return {
        "user_id": row["user_id"],
        "predicted_trust_score": _clamp(scores[idx]),
        "top_reason": top_reason,
        "top_reasons": top,
        "highlights": {k: _jsonable(row[k]) if k in row.index else None
                       for k in LB_HIGHLIGHT_KEYS},
    }


def _leaderboard_payload():
    if "leaderboard" in _CACHE:
        return _CACHE["leaderboard"]

    scores = _cached_scores()
    top_idx = scores.nlargest(10).index
    bottom_idx = scores.nsmallest(10).index
    median = float(scores.median())
    mid_idx = (scores - median).abs().nsmallest(10).index

    # One batch SHAP call for just the 30 featured rows - never all ~5000.
    all_idx = list(dict.fromkeys([*top_idx, *mid_idx, *bottom_idx]))
    shap_rows = shap_matrix(_df.loc[all_idx])
    reasons = {i: items_from_shap(row) for i, row in zip(all_idx, shap_rows)}

    middle = sorted(mid_idx, key=lambda i: -scores[i])
    payload = {
        "top": [_lb_entry(scores, i, reasons[i]) for i in top_idx],
        "middle": [_lb_entry(scores, i, reasons[i]) for i in middle],
        "bottom": [_lb_entry(scores, i, reasons[i]) for i in bottom_idx],
        "score_median": _clamp(median),
        "population_count": int(len(scores)),
    }
    _CACHE["leaderboard"] = payload
    return payload


@app.route("/api/leaderboard")
def leaderboard():
    """Top / middle / bottom 10 from the one-time score cache."""
    return jsonify(_leaderboard_payload())


@app.route("/api/simulate", methods=["POST"])
def simulate():
    """Score a hypothetical worker from feature values in the request body.

    Same response shape as GET /api/user/<id> minus user_id, so the frontend
    can reuse its parsing/rendering code; skips the CSV lookup entirely.
    """
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "Expected a JSON object of feature values."}), 400

    def _bad(value):
        if value is None:
            return True
        try:
            return bool(pd.isna(float(value)))
        except (TypeError, ValueError):
            return True

    missing = [f for f in REQUIRED_FIELDS if _bad(body.get(f))]
    if missing:
        return jsonify({
            "error": "Missing/invalid required fields for this worker.",
            "missing_fields": missing,
        }), 422

    payload = {f: _jsonable(body.get(f)) for f in MODEL_FEATURES}

    # Optional "consent" (string or list) mirrors the GET ?consent= gate so
    # the Data & Consent page can preview a withheld source on a simulate.
    granted = _parse_consent(body.get("consent"))
    for feature, source in SOURCE_OF_FEATURE.items():
        if source not in granted:
            payload[feature] = None
    _fix_completeness(payload)

    cohort = {c: _jsonable(body.get(c)) for c in COHORT_COLS}
    if all(v is not None for v in cohort.values()):
        payload.update(cohort)  # enables cohort-median imputation
    else:
        cohort = {c: None for c in COHORT_COLS}

    ml_result = get_prediction_and_explanation(payload)
    return jsonify(_result_payload(payload, cohort, granted, ml_result))


@app.route("/api/compare", methods=["GET"])
def compare():
    """Two workers side by side + the SHAP features driving their gap."""
    a = (request.args.get("a") or "").strip()
    b = (request.args.get("b") or "").strip()
    if not a or not b:
        return jsonify({"error": "Both ?a= and ?b= user ids are required."}), \
            400

    sides = {}
    for side, uid in (("user_a", a), ("user_b", b)):
        record, search_id, err = _lookup_record(uid)
        if err is not None:
            resp, code = err
            data = resp.get_json() or {}
            data["side"] = side
            return jsonify(data), code

        granted = _parse_consent(None)  # comparison view: all sources shared
        payload, cohort = _build_payload(record, granted)
        ml_result = get_prediction_and_explanation(payload)
        sides[side] = _result_payload(payload, cohort, granted, ml_result,
                                      user_id=search_id)

    # Features whose SHAP contribution differs most between the two workers
    # (plain diff of per-feature contributions - no new model logic).
    ra = {r["key"]: r["impact_points"] for r in sides["user_a"]["reasons"]}
    rb = {r["key"]: r["impact_points"] for r in sides["user_b"]["reasons"]}
    diff_reasons = sorted(
        ({
            "key": key,
            "feature": FEATURE_LABELS.get(key, key),
            "impact_a": ra[key],
            "impact_b": rb[key],
            "delta": round(ra[key] - rb[key], 1),
        } for key in ra),
        key=lambda d: -abs(d["delta"]),
    )[:5]

    return jsonify({
        "user_a": sides["user_a"],
        "user_b": sides["user_b"],
        "score_gap": (sides["user_a"]["predicted_trust_score"]
                      - sides["user_b"]["predicted_trust_score"]),
        "diff_reasons": diff_reasons,
    })


def _fairness_payload():
    if "fairness" in _CACHE:
        return _CACHE["fairness"]

    scores = _cached_scores().map(_clamp)  # displayed 300-900 int scores
    X = _cached_features()                 # imputed model inputs
    vol = X["cohort_adjusted_volatility"]
    raw_cv = _df["raw_income_cv"]          # diagnostic column (not a feature)

    # Healthy mask - thresholds imported from ml/fairness_check.py so the
    # page and the training-time fairness gate can never drift apart.
    healthy = (
        (X["income_floor_ratio"] >= HEALTHY_FLOOR_MIN)
        & (X["income_trend_slope"] >= HEALTHY_TREND_MIN)
        & (X["utility_on_time_ratio"] >= HEALTHY_UTILITY_MIN)
    )

    # 1) Avg score by DECOMPOSED volatility decile (seasonality vs. peers
    #    already removed) - overall AND within the otherwise-healthy
    #    subgroup. The overall line can fall (high unusual swings correlate
    #    with genuinely riskier patterns); the healthy line is the PS's
    #    claim: healthy variability must NOT be punished.
    decile = pd.qcut(vol.rank(method="first"), 10, labels=False) + 1
    by_decile = []
    for d in range(1, 11):
        m = decile == d
        if not bool(m.any()):
            continue
        hm = m & healthy
        by_decile.append({
            "decile": d,
            "count": int(m.sum()),
            "avg_score": round(float(scores[m].mean()), 1),
            "healthy_count": int(hm.sum()),
            "avg_score_healthy": (round(float(scores[hm].mean()), 1)
                                  if bool(hm.any()) else None),
            "avg_cohort_adjusted_volatility": round(float(vol[m].mean()), 3),
            "avg_raw_income_cv": round(float(raw_cv[m].mean()), 3),
        })

    # 2) The fairness claim in two numbers: workers with HIGH RAW swings but
    #    a healthy underlying pattern vs. smooth (low-raw-swing) workers.
    hi = raw_cv >= raw_cv.quantile(0.75)
    lo = raw_cv <= raw_cv.quantile(0.25)

    hi_avg = round(float(scores[hi & healthy].mean()), 1)
    lo_avg = round(float(scores[lo].mean()), 1)
    lo_healthy_avg = round(float(scores[lo & healthy].mean()), 1)

    # 3) Global SHAP importance over the full dataset - ONE batch call,
    #    cached; never per request.
    mat = shap_matrix(_df)
    imp = pd.Series(np.abs(mat).mean(axis=0), index=MODEL_FEATURES)
    global_feature_importance = [
        {"feature": f, "label": FEATURE_LABELS.get(f, f),
         "mean_abs_shap": round(float(v), 2)}
        for f, v in imp.sort_values(ascending=False).items()
    ]

    population_stats = {
        "count": int(len(scores)),
        "min": int(scores.min()),
        "median": round(float(scores.median()), 1),
        "max": int(scores.max()),
        "mean": round(float(scores.mean()), 1),
        "std": round(float(scores.std()), 1),
    }

    edges = list(range(300, 901, 50))  # 12 buckets: 300-350 ... 850-900
    counts, _ = np.histogram(np.clip(scores.values, 300, 900), bins=edges)
    score_histogram = [
        {"bucket": f"{edges[i]}-{edges[i + 1]}", "count": int(counts[i])}
        for i in range(len(counts))
    ]

    payload = {
        "score_by_volatility_decile": by_decile,
        "healthy_decile_gap": (
            round(by_decile[-1]["avg_score_healthy"]
                  - by_decile[0]["avg_score_healthy"], 1)
            if (by_decile[0]["avg_score_healthy"] is not None
                and by_decile[-1]["avg_score_healthy"] is not None) else None),
        "healthy_high_volatility_avg_score": hi_avg,
        "low_volatility_avg_score": lo_avg,
        "healthy_low_volatility_avg_score": lo_healthy_avg,
        "high_volatility_healthy_count": int((hi & healthy).sum()),
        "low_volatility_count": int(lo.sum()),
        "gap_vs_low_volatility": round(hi_avg - lo_avg, 1),
        "max_acceptable_gap_points": MAX_ACCEPTABLE_GAP,
        "group_definition": (
            "High raw volatility = top quartile of raw_income_cv (diagnostic "
            "column) AND healthy pattern (income floor >= 0.25, non-negative "
            "trend, utility on-time >= 0.70). Low raw volatility = bottom "
            "quartile of raw_income_cv."
        ),
        "global_feature_importance": global_feature_importance,
        "population_stats": population_stats,
        "score_histogram": score_histogram,
    }
    _CACHE["fairness"] = payload
    return payload


@app.route("/api/fairness")
def fairness():
    """Precomputed population/fairness aggregates (cached once)."""
    return jsonify(_fairness_payload())


# Simulator slider metadata: dataset medians/ranges + display formats, so
# "Reset to typical gig worker" starts from a realistic baseline.
SIM_FORMATS = {
    "monthly_avg_income": "money",
    "cohort_adjusted_volatility": "signed_pct",
    "income_trend_slope": "signed_pct",
    "income_floor_ratio": "ratio",
    "earning_gap_irregularity": "days",
    "upi_monthly_txns": "int",
    "utility_on_time_ratio": "ratio",
    "platform_tenure_months": "months",
    "avg_platform_rating": "rating",
    "rating_trend": "signed_num",
    "avg_savings_ratio": "ratio",
    "data_completeness": "ratio",
}

SIM_STEPS = {
    "monthly_avg_income": 500,
    "cohort_adjusted_volatility": 0.005,
    "income_trend_slope": 0.0005,
    "income_floor_ratio": 0.01,
    "earning_gap_irregularity": 0.05,
    "upi_monthly_txns": 1,
    "utility_on_time_ratio": 0.01,
    "platform_tenure_months": 1,
    "avg_platform_rating": 0.05,
    "rating_trend": 0.01,
    "avg_savings_ratio": 0.005,
    "data_completeness": 0.01,
}


def _defaults_payload():
    if "defaults" in _CACHE:
        return _CACHE["defaults"]

    X = _cached_features()
    medians, ranges = {}, {}
    for f in MODEL_FEATURES:
        col = pd.to_numeric(X[f], errors="coerce").dropna()
        medians[f] = round(float(col.median()), 4)
        ranges[f] = [round(float(col.min()), 4), round(float(col.max()), 4)]

    payload = {
        "medians": medians,
        "ranges": ranges,
        "steps": SIM_STEPS,
        "formats": SIM_FORMATS,
        "labels": {f: FEATURE_LABELS.get(f, f) for f in MODEL_FEATURES},
    }
    _CACHE["defaults"] = payload
    return payload


@app.route("/api/feature_defaults")
def feature_defaults():
    """Dataset medians/ranges/format hints for the Score Simulator."""
    return jsonify(_defaults_payload())


if __name__ == "__main__":
    debug = os.environ.get("FLASK_DEBUG", "0") == "1"
    if debug:
        print("WARNING: FLASK_DEBUG=1 - development only. Never expose "
              "debug mode on a network or during a demo (RCE console).")
    ssl_context = "adhoc" if os.environ.get("KARM_HTTPS", "0") == "1" else None
    app.run(
        host=os.environ.get("KARM_HOST", "0.0.0.0"),
        port=int(os.environ.get("KARM_PORT", "5000")),
        debug=debug,
        ssl_context=ssl_context,  # KARM_HTTPS=1 needs the pyopenssl package
    )
