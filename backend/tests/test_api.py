"""Endpoint tests for the Flask API (P4).

Happy paths for every endpoint + the 404 and 422 error paths.

Run from backend/:   python -m pytest tests -q
(Full ML pipeline runs first in CI - see .github/workflows/ci.yml.)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import app  # noqa: E402

VALID_SIM_BODY = {
    "monthly_avg_income": 32000.0,
    "cohort_adjusted_volatility": 0.05,
    "income_trend_slope": 0.002,
    "income_floor_ratio": 0.55,
    "earning_gap_irregularity": 1.8,
    "platform_tenure_months": 24.0,
    "avg_savings_ratio": 0.18,
    "upi_monthly_txns": 60.0,
    "utility_on_time_ratio": 0.90,
    "avg_platform_rating": 4.4,
    "rating_trend": 0.05,
    "data_completeness": 1.0,
}


def _client():
    app.config["TESTING"] = True
    return app.test_client()


# ---------------------------------------------------------------- health ---
def test_health_ok():
    r = _client().get("/api/health")
    assert r.status_code == 200
    body = r.get_json()
    assert body["status"] == "ok"
    assert isinstance(body["cache_built"], bool)


# ------------------------------------------------------------------ user ---
def test_user_happy_path():
    r = _client().get("/api/user/GIG_0001")
    assert r.status_code == 200
    d = r.get_json()
    assert d["user_id"] == "GIG_0001"
    assert 300 <= d["predicted_trust_score"] <= 900
    assert isinstance(d["summary"], str) and d["summary"]
    assert isinstance(d["reasons"], list) and d["reasons"]
    assert isinstance(d["counterfactuals"], list)
    assert len(d["radar_values"]) == 5
    assert d["features"]["data_completeness"] is not None
    # all three optional sources granted by default
    assert sorted(d["consent"]) == ["platform", "upi", "utility"]


def test_user_404_unknown_id():
    r = _client().get("/api/user/DOES_NOT_EXIST")
    assert r.status_code == 404
    body = r.get_json()
    assert "error" in body
    # payload suggests real ids
    ids = " ".join(body.get("sample_ids", []))
    assert "GIG_" in ids or "TWIN_" in ids


def test_user_consent_query_echoed():
    r = _client().get("/api/user/GIG_0001?consent=utility")
    assert r.status_code == 200
    assert sorted(r.get_json()["consent"]) == ["utility"]


# ------------------------------------------------------------ leaderboard ---
def test_leaderboard_happy_path():
    r = _client().get("/api/leaderboard")
    assert r.status_code == 200
    d = r.get_json()
    for tab in ("top", "middle", "bottom"):
        assert len(d[tab]) == 10
        entry = d[tab][0]
        assert "user_id" in entry
        assert 300 <= entry["predicted_trust_score"] <= 900
        assert isinstance(entry["top_reasons"], list)
        assert isinstance(entry["highlights"], dict)
    assert d["population_count"] >= 5000
    assert 300 <= d["score_median"] <= 900
    # tabs must be ordered: top >= middle >= bottom (first entries)
    assert d["top"][0]["predicted_trust_score"] >= d["bottom"][0]["predicted_trust_score"]


# -------------------------------------------------------------- simulator ---
def test_simulate_happy_path():
    r = _client().post("/api/simulate", json=VALID_SIM_BODY)
    assert r.status_code == 200
    d = r.get_json()
    assert 300 <= d["predicted_trust_score"] <= 900
    assert isinstance(d["summary"], str)
    assert isinstance(d["reasons"], list) and d["reasons"]
    assert isinstance(d["counterfactuals"], list)
    assert len(d["radar_values"]) == 5
    assert "cohort" in d


def test_simulate_missing_fields_422():
    r = _client().post("/api/simulate", json={})
    assert r.status_code == 422
    body = r.get_json()
    assert body["error"]
    assert isinstance(body["missing_fields"], list) and body["missing_fields"]


def test_simulate_non_object_body_400():
    r = _client().post(
        "/api/simulate", data="not-json", content_type="text/plain")
    assert r.status_code == 400


# ---------------------------------------------------------------- compare ---
def test_compare_twins_happy_path():
    r = _client().get("/api/compare?a=TWIN_HEALTHY&b=TWIN_RISKY")
    assert r.status_code == 200
    d = r.get_json()
    assert d["user_a"]["user_id"] == "TWIN_HEALTHY"
    assert d["user_b"]["user_id"] == "TWIN_RISKY"
    # the whole point of the fixture: identical averages, different scores
    assert abs(d["score_gap"]) > 150
    assert isinstance(d["diff_reasons"], list) and d["diff_reasons"]
    top = d["diff_reasons"][0]
    assert {"feature", "impact_a", "impact_b", "delta"} <= set(top.keys())


def test_compare_requires_both_ids():
    r = _client().get("/api/compare?a=TWIN_HEALTHY")
    assert r.status_code == 400
    r2 = _client().get("/api/compare?a=&b=")
    assert r2.status_code == 400


def test_compare_unknown_user_404():
    r = _client().get("/api/compare?a=DOES_NOT_EXIST&b=TWIN_RISKY")
    assert r.status_code == 404


# --------------------------------------------------------------- fairness ---
def test_fairness_happy_path():
    r = _client().get("/api/fairness")
    assert r.status_code == 200
    d = r.get_json()
    dec = d["score_by_volatility_decile"]
    assert len(dec) == 10
    assert dec[0]["decile"] == 1 and dec[-1]["decile"] == 10
    assert isinstance(d["healthy_decile_gap"], (int, float))
    # training-time gate: healthy workers' decile gap within tolerance
    assert d["healthy_decile_gap"] >= -d["max_acceptable_gap_points"]
    assert len(d["score_histogram"]) == 12
    assert isinstance(d["global_feature_importance"], list)
    assert d["global_feature_importance"][0]["mean_abs_shap"] >= 0
    pop = d["population_stats"]
    assert pop["count"] >= 5000
    assert 300 <= pop["min"] <= pop["median"] <= pop["max"] <= 900


# -------------------------------------------------------- feature defaults ---
def test_feature_defaults_happy_path():
    r = _client().get("/api/feature_defaults")
    assert r.status_code == 200
    d = r.get_json()
    for key in ("medians", "ranges", "steps", "formats", "labels"):
        assert isinstance(d[key], dict) and d[key]
    # every slider key is covered consistently
    assert set(d["labels"]) == set(d["medians"]) == set(d["ranges"])
    assert set(d["labels"]) == set(d["steps"]) == set(d["formats"])
    for feature, rng in d["ranges"].items():
        assert len(rng) == 2 and rng[0] < rng[1], feature
