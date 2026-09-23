"""
Fairness check for the exact bias the problem statement warns about.

Question we answer for the judges/deck:
    "Does the model systematically give LOWER scores to workers with high
     cohort-adjusted volatility (unusual swings for their line of work)
     when everything else about them looks healthy - strong floor, flat or
     growing trend, reliable utility payments?"

Method:
    1. Bucket the held-out eval set into deciles of
       cohort_adjusted_volatility (D1 = smoothest, D10 = wildest).
    2. Within each decile, isolate the 'otherwise-healthy' subgroup
       (floor >= 0.25 - their worst week keeps at least a quarter of
       typical earnings, trend >= 0, utility on-time >= 0.70 - using the
       same imputed values the model actually sees).
    3. Compare mean predicted score of the healthy subgroup in D10 vs D1.
       If D10 is not meaningfully lower (>= -30 points, i.e. 5% of the
       300-900 range), the model is NOT punishing unusual-but-healthy
       volatility: PASS.

Run standalone from backend/:   python ml/fairness_check.py
"""

import os
import sys

import joblib
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from data_utils import (  # noqa: E402
    COHORT_COLS,
    IMPUTER_PATH,
    MODEL_FEATURES,
    MODEL_DIR,
    fit_imputer,
    impute,
    load_dataset,
    load_imputer,
)

HEALTHY_FLOOR_MIN = 0.25  # worst week keeps >=25% of typical earnings
HEALTHY_TREND_MIN = 0.0
HEALTHY_UTILITY_MIN = 0.70
MAX_ACCEPTABLE_GAP = 30.0  # points; 5% of the 300-900 score range


def run_fairness_check(model, X_test_raw, X_test_imp, y_test=None,
                       preds=None, verbose=True) -> dict:
    if preds is None:
        preds = model.predict(X_test_imp[MODEL_FEATURES])

    vol = X_test_raw["cohort_adjusted_volatility"]
    decile = pd.qcut(vol.rank(method="first"), 10, labels=False)

    healthy = (
        (X_test_imp["income_floor_ratio"] >= HEALTHY_FLOOR_MIN)
        & (X_test_imp["income_trend_slope"] >= HEALTHY_TREND_MIN)
        & (X_test_imp["utility_on_time_ratio"] >= HEALTHY_UTILITY_MIN)
    )

    rows = []
    for d in range(10):
        m = decile == d
        hm = m & healthy
        rows.append({
            "decile": f"D{d + 1}",
            "vol_range": f"[{vol[m].min():+.3f}, {vol[m].max():+.3f}]",
            "n": int(m.sum()),
            "mean_pred_all": float(np.mean(preds[m])),
            "n_healthy": int(hm.sum()),
            "mean_pred_healthy": float(np.mean(preds[hm])) if hm.any() else np.nan,
            "mean_actual_healthy": (float(np.mean(y_test[hm]))
                                     if y_test is not None and hm.any() else np.nan),
        })
    table = pd.DataFrame(rows).set_index("decile")

    d10 = table.loc["D10", "mean_pred_healthy"]
    d1 = table.loc["D1", "mean_pred_healthy"]
    gap = float(d10 - d1)
    passed = bool(gap >= -MAX_ACCEPTABLE_GAP)
    verdict = "PASS" if passed else "FAIL"

    if verbose:
        print(table.round(1).to_string())
        print(f"\nHealthy D10 - Healthy D1 mean score gap: {gap:+.1f} points "
              f"(tolerance: >= -{MAX_ACCEPTABLE_GAP:.0f})")
        print(f"Verdict: {verdict} - high-cohort-volatility workers who are "
              f"otherwise healthy are {'NOT ' if passed else ''}systematically "
              f"scored lower.")

    return {
        "passed": passed,
        "gap_points": gap,
        "table": table,
    }


def main() -> None:
    df = load_dataset()
    y = df["trust_score"]
    X_raw = df[MODEL_FEATURES + COHORT_COLS]
    _, X_test_raw, _, y_test = train_test_split(
        X_raw, y, test_size=0.2, random_state=42)

    if not os.path.exists(IMPUTER_PATH):
        imputer = fit_imputer(X_raw)  # fallback; normal path uses train fit
    else:
        imputer = load_imputer()
    X_test_imp = impute(X_test_raw, imputer)[MODEL_FEATURES]

    model = joblib.load(os.path.join(MODEL_DIR, "lgbm_model.pkl"))
    result = run_fairness_check(model, X_test_raw, X_test_imp, y_test=y_test)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
