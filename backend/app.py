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
from ml.predict import get_prediction_and_explanation

app = Flask(__name__)

ALLOWED_ORIGINS = [
    o.strip() for o in os.environ.get(
        "ALLOWED_ORIGINS",
        "http://localhost:5000,http://127.0.0.1:5000",
    ).split(",") if o.strip()
]
CORS(app, origins=ALLOWED_ORIGINS)

# ---------------------------------------------------------------------------
# Storage: in-memory SQLite (parameterized queries; CSV loaded once at boot)
# ---------------------------------------------------------------------------
_db = sqlite3.connect(":memory:", check_same_thread=False)
load_dataset().to_sql("users", _db, index=False)

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


@app.route("/api/user/<user_id>", methods=["GET"])
def get_user_data(user_id):
    search_id = str(user_id).strip().upper()

    row = pd.read_sql_query(
        "SELECT * FROM users WHERE upper(trim(user_id)) = ? LIMIT 1",
        _db, params=(search_id,))
    if row.empty:
        sample = pd.read_sql_query(
            "SELECT user_id FROM users LIMIT 3", _db)["user_id"].tolist()
        return jsonify({
            "error": f"User ID '{user_id}' not found.",
            "sample_ids_in_csv": sample,
        }), 404
    record = row.iloc[0]

    # --- validate required fields before scoring --------------------------
    missing = [f for f in REQUIRED_FIELDS if f not in record.index
               or record[f] is None or pd.isna(record[f])]
    if missing:
        return jsonify({
            "error": "Malformed profile: required fields missing or invalid.",
            "user_id": search_id,
            "missing_fields": missing,
        }), 422

    payload = {f: _jsonable(record[f]) if f in record.index else None
               for f in MODEL_FEATURES}

    # --- consent gate: sources not granted never reach the model ----------
    consent_arg = request.args.get("consent")
    if consent_arg is None:
        granted = set(GRANTABLE_SOURCES)  # web dashboard default: all granted
    else:
        granted = {s.strip().lower() for s in consent_arg.split(",")
                   if s.strip()} & GRANTABLE_SOURCES
        for feature, source in SOURCE_OF_FEATURE.items():
            if source not in granted:
                payload[feature] = None  # nulled, then cohort-imputed downstream

    # Effective completeness reflects BOTH originally-missing sources and
    # sources the user just declined to share (min of the two).
    originally = payload.get("data_completeness")
    present = sum(1 for f in COMPLETENESS_PRIMARIES
                  if payload.get(f) is not None)
    observed = present / len(COMPLETENESS_PRIMARIES)
    payload["data_completeness"] = (min(originally, observed)
                                    if originally is not None else observed)

    cohort = {c: _jsonable(record[c]) for c in COHORT_COLS
              if c in record.index}
    for c in COHORT_COLS:
        payload[c] = cohort.get(c)

    ml_result = get_prediction_and_explanation(payload)

    # --- radar axes: Income Floor / UPI / Utility / Rating / Savings ------
    def axis(value, scale, cap=100.0, default=50.0):
        if value is None:
            return default
        return round(min(float(value) * scale, cap), 1)

    radar_values = [
        axis(payload["income_floor_ratio"], 100.0),
        axis(payload["upi_monthly_txns"], 100.0 / 2.5),
        axis(payload["utility_on_time_ratio"], 100.0),
        axis(payload["avg_platform_rating"], 20.0),
        axis(payload["avg_savings_ratio"], 200.0),
    ]

    return jsonify({
        "user_id": search_id,
        "cohort": cohort,
        "consent": sorted(granted),
        "features": payload,
        "predicted_trust_score": ml_result["predicted_trust_score"],
        "summary": ml_result["summary"],
        "reasons": ml_result["reasons"],
        "counterfactuals": ml_result["counterfactuals"],
        "radar_values": radar_values,
    })


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
