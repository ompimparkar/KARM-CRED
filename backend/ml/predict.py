import pandas as pd

from .explainability import TrustScoreExplainer

_explainer_instance = None


def _get_explainer() -> TrustScoreExplainer:
    global _explainer_instance
    if _explainer_instance is None:
        _explainer_instance = TrustScoreExplainer()
    return _explainer_instance


def get_prediction_and_explanation(payload):
    """Single-record path: score + summary + SHAP reasons + counterfactuals."""
    return _get_explainer().explain_score(payload)


def score_dataframe(df: pd.DataFrame) -> pd.Series:
    """Vectorized batch scoring for a whole frame (ONE model.predict call).

    Used by the dashboard's population views (leaderboard, fairness stats) so
    we never loop row-by-row through the single-user explainer. Returns a
    Series aligned to df.index with the raw (unclamped) model output.
    """
    explainer = _get_explainer()
    scores = explainer.predict_many(df)
    return pd.Series(scores, index=df.index, name="predicted_trust_score")


def shap_matrix(df: pd.DataFrame):
    """SHAP matrix (n_rows x n_features) for a whole frame - batch path."""
    return _get_explainer().shap_matrix(df)


def features_frame(df: pd.DataFrame) -> pd.DataFrame:
    """The exact imputed, ordered model input frame for a whole frame."""
    return _get_explainer().features_frame(df)
