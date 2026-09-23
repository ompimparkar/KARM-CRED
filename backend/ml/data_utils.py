"""
Shared feature contract + missing-data (imputation) strategy for KARM CRED.

Missing-data policy (documented here and in MODEL_CARD.md):

Gig workers are often "invisible" to one data source or another:
  - a renter may have NO formal utility bill in their name,
  - a cash/wallet-paid worker may have NO UPI history,
  - a worker on one platform may have no cross-platform rating.

We never silently ignore a missing field and never crash on it:
  1. Missing values are filled with the COHORT MEDIAN (same gig / city /
     vehicle), falling back to the training-set global median when the
     cohort value or the cohort itself is unknown (cold start).
  2. A `data_completeness` indicator feature (0.0 - 1.0, fraction of the
     three optional sources actually available) travels with every request,
     so the model can damp its reliance on imputed evidence and the UI can
     be honest about a thin file instead of pretending it is a full one.

Imputation statistics are FIT ON TRAINING DATA ONLY and persisted to
saved_models/imputer.json - serving time (explainability.py) loads that file,
so the model never sees test-time distribution information at fit time.
"""

import json
import os

import numpy as np
import pandas as pd

# ---------------------------------------------------------------------------
# Contract: the exact model input vector, in order (keep in sync with
# generate_data.py CSV, explainability.py labels, and MODEL_CARD.md).
# ---------------------------------------------------------------------------
MODEL_FEATURES = [
    "monthly_avg_income",
    "cohort_adjusted_volatility",
    "income_trend_slope",
    "income_floor_ratio",
    "earning_gap_irregularity",
    "upi_monthly_txns",
    "utility_on_time_ratio",
    "platform_tenure_months",
    "avg_savings_ratio",
    "avg_platform_rating",
    "rating_trend",
    "data_completeness",
]

COHORT_COLS = ["gig_type", "city", "vehicle_class"]

# Which consent/data source each optional feature comes from. Used by the
# consent gate in app.py: a source the user has NOT consented to has its
# features nulled before they ever reach the model.
SOURCE_OF_FEATURE = {
    "upi_monthly_txns": "upi",              # UPI / bank SMS metadata
    "utility_on_time_ratio": "utility",     # utility bill reminders
    "avg_platform_rating": "platform",      # gig-platform profile/rating
    "rating_trend": "platform",
}

# Primary field per optional source; completeness = fraction of these present.
COMPLETENESS_PRIMARIES = [
    "utility_on_time_ratio",
    "upi_monthly_txns",
    "avg_platform_rating",
]

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_CSV = os.path.join(BASE_DIR, "..", "data", "karm_cred_synthetic_data.csv")
MODEL_DIR = os.path.join(BASE_DIR, "saved_models")
IMPUTER_PATH = os.path.join(MODEL_DIR, "imputer.json")


def load_dataset() -> pd.DataFrame:
    df = pd.read_csv(DATA_CSV)
    df.columns = df.columns.str.strip().str.lower()
    return df


def _row_frame(data) -> pd.DataFrame:
    if isinstance(data, pd.DataFrame):
        return data.copy()
    return pd.DataFrame([dict(data)])


def _cohort_keys(df: pd.DataFrame):
    """Cohort key 'gig|city|vehicle', or None when cohort is unknown."""
    if all(c in df.columns for c in COHORT_COLS):
        return (df[COHORT_COLS[0]].astype(str) + "|" +
                df[COHORT_COLS[1]].astype(str) + "|" +
                df[COHORT_COLS[2]].astype(str))
    return None


def fit_imputer(df: pd.DataFrame) -> dict:
    """Fit global + cohort medians on the TRAINING frame (with cohort cols)."""
    keys = _cohort_keys(df)
    global_medians = {}
    cohort_medians = {f: {} for f in MODEL_FEATURES}
    for f in MODEL_FEATURES:
        col = pd.to_numeric(df[f], errors="coerce") if f in df.columns \
            else pd.Series(dtype=float)
        global_medians[f] = float(col.median()) if col.notna().any() else 0.0
        if keys is not None and f in df.columns:
            med = df.assign(_k=keys).groupby("_k")[f].median()
            cohort_medians[f] = {
                k: float(v) for k, v in med.items() if not pd.isna(v)
            }
    return {
        "features": list(MODEL_FEATURES),
        "global_medians": global_medians,
        "cohort_medians": cohort_medians,
    }


def save_imputer(imputer: dict, path: str = IMPUTER_PATH) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(imputer, fh, indent=2, sort_keys=True)


def load_imputer(path: str = IMPUTER_PATH) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def impute(data, imputer: dict) -> pd.DataFrame:
    """
    Return a frame safe for the model:
      - optional features missing (None / NaN / absent key) filled with
        cohort median -> global median,
      - data_completeness = min(provided, present_primaries / 3) so consent
        withdrawals and cold-start payloads are reflected honestly.
    """
    df = _row_frame(data)
    keys = _cohort_keys(df)

    # --- completeness, computed BEFORE imputation fills anything -----------
    present = pd.Series(0.0, index=df.index)
    for f in COMPLETENESS_PRIMARIES:
        if f in df.columns:
            present += pd.to_numeric(df[f], errors="coerce").notna().astype(float)
    observed = present / len(COMPLETENESS_PRIMARIES)
    if "data_completeness" in df.columns:
        provided = pd.to_numeric(df["data_completeness"], errors="coerce")
        df["data_completeness"] = np.fmin(provided, observed)
    else:
        df["data_completeness"] = observed
    df["data_completeness"] = df["data_completeness"].clip(0.0, 1.0)

    # --- per-feature imputation -------------------------------------------
    for f in MODEL_FEATURES:
        if f == "data_completeness":
            continue
        if f in df.columns:
            s = pd.to_numeric(df[f], errors="coerce")
        else:
            s = pd.Series(np.nan, index=df.index)
        missing = s.isna()
        if missing.any():
            g = imputer["global_medians"].get(f, 0.0)
            fill = pd.Series(g, index=df.index, dtype=float)
            if keys is not None and imputer["cohort_medians"].get(f):
                cmap = imputer["cohort_medians"][f]
                fill.loc[missing] = [cmap.get(k, g) for k in keys[missing]]
            s = s.mask(missing, fill)
        df[f] = s

    return df
