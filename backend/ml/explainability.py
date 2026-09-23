"""
Score explanation for KARM CRED.

Produces, for one worker:
  - predicted TrustScore (clamped 300-900),
  - SHAP reason cards with PLAIN-LANGUAGE labels (a delivery rider must be
    able to read them - no raw feature names),
  - a one-line natural-language summary above the cards, e.g.
    "Your score is held back mainly by unpredictable gaps between the days
     you earn, with a strong income floor your biggest strength.",
  - counterfactual suggestions ("Increasing your utility on-time ratio to
    90% would add ~+35 points") computed by re-running the model on a
    single edited feature at a time.

Missing fields (cold start / denied consent) are imputed here from
saved_models/imputer.json (cohort median -> global median) and surfaced via
the data_completeness feature - this module never crashes on a missing key.
"""

import os

import joblib
import numpy as np
import pandas as pd
import shap

try:  # package import (serving path via ml.predict)
    from .data_utils import IMPUTER_PATH, MODEL_FEATURES, impute, load_imputer
except ImportError:  # direct script import
    from data_utils import IMPUTER_PATH, MODEL_FEATURES, impute, load_imputer

MODEL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "saved_models", "lgbm_model.pkl")

# Reason-card titles: feature -> text a gig worker can actually read.
FEATURE_LABELS = {
    "monthly_avg_income": "Average Monthly Earnings",
    "cohort_adjusted_volatility": "Income Swings vs. Similar Gig Workers",
    "income_trend_slope": "Earnings Trend (Growing or Shrinking)",
    "income_floor_ratio": "Income Floor (Worst Week vs. Typical Week)",
    "earning_gap_irregularity": "Regularity of Gaps Between Paydays",
    "upi_monthly_txns": "UPI Payment Activity",
    "utility_on_time_ratio": "Utility Bill Punctuality",
    "platform_tenure_months": "Track Record on Gig Platforms",
    "avg_savings_ratio": "Monthly Savings Buffer",
    "avg_platform_rating": "Platform Rating (Average Stars)",
    "rating_trend": "Platform Rating Trend",
    "data_completeness": "Data Completeness (Partial vs. Full Profile)",
}

# Sentence fragments for the one-line summary: (when it HURTS, when it HELPS)
PHRASES = {
    "monthly_avg_income": ("lower earnings than the workers we typically see",
                           "solid average earnings"),
    "cohort_adjusted_volatility": (
        "income swings that are unusual for others in your line of work",
        "income swings that are in line with your peers"),
    "income_trend_slope": ("a sustained decline in your recent earnings",
                           "a growing earnings trend"),
    "income_floor_ratio": (
        "a thin income floor - your worst weeks drop far below your typical week",
        "a strong income floor - even your worst weeks hold up"),
    "earning_gap_irregularity": (
        "unpredictable gaps between the days you earn",
        "regular, predictable earning days"),
    "upi_monthly_txns": ("low UPI payment activity",
                         "consistent UPI payment activity"),
    "utility_on_time_ratio": ("late utility-bill payments",
                              "reliable utility-bill payments"),
    "platform_tenure_months": ("a short track record on gig platforms",
                               "a long track record on gig platforms"),
    "avg_savings_ratio": ("a thin savings buffer", "a healthy savings buffer"),
    "avg_platform_rating": ("a below-average platform rating",
                            "a strong platform rating"),
    "rating_trend": ("a declining platform rating",
                     "an improving platform rating"),
    "data_completeness": ("having very little data to work with",
                          "a complete data profile"),
}

# Counterfactual levers: (feature, target, action text, applicable?)
# Each is evaluated ONE feature at a time; only suggestions that move the
# displayed score by >= 3 points are surfaced, best first (top 3).
COUNTERFACTUAL_SPECS = [
    ("utility_on_time_ratio", 0.90,
     "Increase your utility-bill on-time ratio to 90%",
     lambda cur, p: cur < 0.895 and _present(p, "utility_on_time_ratio")),
    ("avg_savings_ratio", 0.25,
     "Raise your monthly savings buffer to 25% of income",
     lambda cur, p: cur < 0.25),
    ("earning_gap_irregularity", 0.50,
     "Aim for a steady earning rhythm: work days at most 1-2 days apart",
     lambda cur, p: cur > 0.50),
    ("avg_platform_rating", 4.5,
     "Lift your platform rating to 4.5 stars",
     lambda cur, p: cur < 4.5 and _present(p, "avg_platform_rating")),
    ("upi_monthly_txns", 100.0,
     "Increase monthly UPI transactions to 100 (route everyday payments via UPI)",
     lambda cur, p: cur < 100 and _present(p, "upi_monthly_txns")),
]


def _present(payload: dict, key: str) -> bool:
    if key not in payload:
        return False
    v = payload[key]
    return v is not None and not pd.isna(v)


def _clamp(score: float) -> int:
    return int(max(300, min(900, round(score))))


def _first(items, kind):
    return next((it for it in items if it["type"] == kind), None)


def build_summary(items) -> str:
    """One natural-language line shown above the reason cards."""
    neg = _first(items, "negative")
    pos = _first(items, "positive")

    def phrase(item, side):
        neg_p, pos_p = PHRASES.get(item["key"], (item["feature"], item["feature"]))
        return (neg_p, pos_p)[side]

    if neg and pos:
        return (f"Your score is held back mainly by {phrase(neg, 0)}, "
                f"with {phrase(pos, 1)} your biggest strength.")
    if neg:
        return (f"Your score is held back mainly by {phrase(neg, 0)}. "
                "Nothing is lifting it in a big way yet.")
    if pos:
        p = phrase(pos, 1)
        return (f"{p[0].upper()}{p[1:]} is your biggest strength, and "
                "nothing is materially holding your score back.")
    return "Your score sits mid-range with no single dominant factor."


class TrustScoreExplainer:
    def __init__(self, model_path: str = MODEL_PATH,
                 imputer_path: str = IMPUTER_PATH):
        self.model = joblib.load(model_path)
        self.explainer = shap.TreeExplainer(self.model)
        self.imputer = load_imputer(imputer_path)
        self.feature_names = list(MODEL_FEATURES)

    def _features_frame(self, payload) -> pd.DataFrame:
        """Imputed, ordered model input; tolerant to missing/extra keys."""
        return impute(payload, self.imputer)[self.feature_names]

    def _counterfactuals(self, df_input: pd.DataFrame, payload: dict,
                         base_score: int) -> list:
        out = []
        current = {f: float(df_input.iloc[0][f]) for f, *_ in COUNTERFACTUAL_SPECS}
        for feat, target, action, applicable in COUNTERFACTUAL_SPECS:
            cur = current[feat]
            if not applicable(cur, payload):
                continue
            edited = df_input.copy()
            edited.iloc[0, edited.columns.get_loc(feat)] = target
            new_score = _clamp(float(self.model.predict(edited)[0]))
            delta = new_score - base_score
            if delta >= 3:
                out.append({
                    "action": action,
                    "feature": feat,
                    "target": float(target),
                    "delta_points": round(float(delta), 1),
                })
        out.sort(key=lambda x: -x["delta_points"])
        return out[:3]

    def explain_score(self, user_features) -> dict:
        payload = dict(user_features)
        df_input = self._features_frame(payload)

        predicted = _clamp(float(self.model.predict(df_input)[0]))
        shap_values = np.asarray(self.explainer.shap_values(df_input))[0]

        items = []
        for feat, val in zip(self.feature_names, shap_values):
            impact = round(float(val), 1)
            items.append({
                "feature": FEATURE_LABELS.get(feat, feat),
                "key": feat,
                "impact_points": impact,
                "type": "positive" if impact >= 0 else "negative",
            })
        items.sort(key=lambda x: abs(x["impact_points"]), reverse=True)

        return {
            "predicted_trust_score": predicted,
            "summary": build_summary(items),
            "reasons": items,
            "counterfactuals": self._counterfactuals(df_input, payload,
                                                     predicted),
        }
