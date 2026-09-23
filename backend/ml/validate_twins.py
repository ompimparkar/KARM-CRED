"""
TWIN FIXTURE - the proof that KARM CRED solves the problem statement's twist.

Two synthetic workers with:
  - identical average monthly income (30,000),
  - identical raw income volatility (CV = 0.38, exactly),
  - identical cohort-adjusted volatility (same cohort, same CV),
  - identical behavioural data (UPI, utility, savings, tenure, rating),
but different *patterns*:
  TWIN_HEALTHY: stable floor (0.73), flat/rising trend, regular paydays
  TWIN_RISKY:   thin floor (0.25), declining trend, erratic disappearing gaps

A model that punishes raw volatility would score them the SAME. This script
asserts ours does not.

Run from backend/:   python ml/validate_twins.py
(also collectable by pytest: the checks are plain test_* functions)
"""

import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from data_utils import MODEL_FEATURES, load_dataset  # noqa: E402
from explainability import TrustScoreExplainer  # noqa: E402

TWIN_IDS = ["TWIN_HEALTHY", "TWIN_RISKY"]
PAYLOAD_COLS = MODEL_FEATURES + ["gig_type", "city", "vehicle_class"]


def get_twins() -> pd.DataFrame:
    df = load_dataset()
    twins = df.set_index("user_id").loc[TWIN_IDS]
    return twins


def payload_for(row: pd.Series) -> dict:
    out = {}
    for col in PAYLOAD_COLS:
        v = row[col] if col in row.index else None
        out[col] = None if (v is None or pd.isna(v)) else v
    return out


def test_twins_share_identical_income_and_volatility():
    """The fixture itself: income & volatility must match EXACTLY."""
    twins = get_twins()
    h, r = twins.loc["TWIN_HEALTHY"], twins.loc["TWIN_RISKY"]
    assert abs(h["monthly_avg_income"] - r["monthly_avg_income"]) < 0.01, \
        "twins must have identical average income"
    assert abs(h["raw_income_cv"] - r["raw_income_cv"]) < 1e-9, \
        "twins must have identical raw volatility"
    assert abs(h["cohort_adjusted_volatility"]
               - r["cohort_adjusted_volatility"]) < 1e-9, \
        "twins must have identical peer-adjusted volatility"
    # ...while their patterns clearly differ
    assert h["income_floor_ratio"] >= 0.55 >= 0.25 >= r["income_floor_ratio"]
    assert h["income_trend_slope"] > 0 > r["income_trend_slope"]
    assert h["earning_gap_irregularity"] * 2 <= r["earning_gap_irregularity"]


def test_model_scores_the_pattern_not_the_lumpiness():
    """The model must separate the twins despite identical volatility."""
    twins = get_twins()
    explainer = TrustScoreExplainer()
    res_h = explainer.explain_score(payload_for(twins.loc["TWIN_HEALTHY"]))
    res_r = explainer.explain_score(payload_for(twins.loc["TWIN_RISKY"]))
    gap = res_h["predicted_trust_score"] - res_r["predicted_trust_score"]
    assert gap >= 100, (
        f"model must score the healthy twin at least 100 points higher, "
        f"got gap={gap} (healthy={res_h['predicted_trust_score']}, "
        f"risky={res_r['predicted_trust_score']})")
    return res_h, res_r


def main() -> None:
    print("=" * 78)
    print("TWIN FIXTURE: identical income + identical volatility,")
    print("healthy vs risky PATTERN")
    print("=" * 78)

    test_twins_share_identical_income_and_volatility()
    print("[PASS] fixture: income, raw CV and cohort-adjusted CV are identical")

    twins = get_twins()
    res_h, res_r = test_model_scores_the_pattern_not_the_lumpiness()
    print(f"[PASS] model gap = {res_h['predicted_trust_score'] - res_r['predicted_trust_score']} "
          f"points (healthy {res_h['predicted_trust_score']} vs "
          f"risky {res_r['predicted_trust_score']})\n")

    show = ["monthly_avg_income", "raw_income_cv", "cohort_adjusted_volatility",
            "income_floor_ratio", "income_trend_slope",
            "earning_gap_irregularity", "utility_on_time_ratio",
            "avg_savings_ratio", "trust_score"]
    table = twins[show].T
    table.columns = ["HEALTHY", "RISKY"]
    print(table.round(4).to_string())
    print(f"\nGround-truth label:  healthy {int(twins.loc['TWIN_HEALTHY', 'trust_score'])}"
          f"  vs  risky {int(twins.loc['TWIN_RISKY', 'trust_score'])}")
    print(f"Model prediction:    healthy {res_h['predicted_trust_score']}"
          f"  vs  risky {res_r['predicted_trust_score']}")
    print(f"\nHealthy summary: {res_h['summary']}")
    print(f"Risky summary:   {res_r['summary']}")
    print("\nTop reason cards (healthy):")
    for r in res_h["reasons"][:4]:
        print(f"  {r['impact_points']:+7.1f}  {r['feature']}")
    print("Top reason cards (risky):")
    for r in res_r["reasons"][:4]:
        print(f"  {r['impact_points']:+7.1f}  {r['feature']}")
    if res_r["counterfactuals"]:
        print("\nRisky twin counterfactuals:")
        for cf in res_r["counterfactuals"]:
            print(f"  +{cf['delta_points']:.0f} pts  {cf['action']}")
    print("\nALL TWIN ASSERTIONS PASSED.")


if __name__ == "__main__":
    main()
