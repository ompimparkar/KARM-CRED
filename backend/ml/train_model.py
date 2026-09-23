"""
Train the KARM CRED LightGBM TrustScore model.

Run from backend/:   python ml/train_model.py

R^2 EXPECTATION: the label is generated from latent roots (repayment
capacity + independent noise), not from a fixed formula over the input
columns, so R^2 will land in a realistic ~0.6-0.85 band rather than the
artificial ~0.99 a recombined-formula label produces. That is intentional -
see MODEL_CARD.md.
"""

import os
import sys

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import mean_squared_error, r2_score
from sklearn.model_selection import train_test_split

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from data_utils import (  # noqa: E402
    COHORT_COLS,
    MODEL_FEATURES,
    MODEL_DIR,
    fit_imputer,
    impute,
    load_dataset,
    save_imputer,
)

try:
    from fairness_check import run_fairness_check
except ImportError:  # when imported as part of the ml package
    from .fairness_check import run_fairness_check

SEED = 42


def main() -> None:
    df = load_dataset()
    y = df["trust_score"]
    # Raw frame keeps NaN + cohort cols: the imputer is FIT on train only.
    X_raw = df[MODEL_FEATURES + COHORT_COLS]

    X_train_raw, X_test_raw, y_train, y_test = train_test_split(
        X_raw, y, test_size=0.2, random_state=SEED)

    imputer = fit_imputer(X_train_raw)
    save_imputer(imputer)
    print(f"Saved imputation stats: {os.path.join(MODEL_DIR, 'imputer.json')}")

    X_train = impute(X_train_raw, imputer)[MODEL_FEATURES]
    X_test = impute(X_test_raw, imputer)[MODEL_FEATURES]
    n_missing = int(df[MODEL_FEATURES].isna().sum().sum())
    print(f"Training rows: {len(X_train)}  |  imputed cells: {n_missing}")

    model = lgb.LGBMRegressor(
        n_estimators=100,
        learning_rate=0.05,
        max_depth=5,
        random_state=SEED,
        verbose=-1,
    )
    model.fit(X_train, y_train)

    y_pred = model.predict(X_test)
    r2 = r2_score(y_test, y_pred)
    rmse = float(np.sqrt(mean_squared_error(y_test, y_pred)))
    print(f"Model R2: {r2:.4f} | RMSE: {rmse:.2f}")
    print("NOTE: R2 in the 0.6-0.85 range is EXPECTED and healthy here - the "
          "label comes from latent capacity + noise, not a recombined formula.")

    print("\nFeature importances (split count):")
    imp = sorted(zip(MODEL_FEATURES, model.feature_importances_),
                 key=lambda kv: kv[1], reverse=True)
    for name, val in imp:
        print(f"  {name:28s} {val}")

    os.makedirs(MODEL_DIR, exist_ok=True)
    model_path = os.path.join(MODEL_DIR, "lgbm_model.pkl")
    joblib.dump(model, model_path)
    print(f"\nSaved model artifact: {model_path}")

    print("\n--- Fairness check (bias vs. naturally volatile workers) ---")
    result = run_fairness_check(
        model, X_test_raw, impute(X_test_raw, imputer)[MODEL_FEATURES],
        y_test=y_test, preds=y_pred)
    if not result["passed"]:
        raise SystemExit("Fairness check FAILED - see table above.")


if __name__ == "__main__":
    main()
