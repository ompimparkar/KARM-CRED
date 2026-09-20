from .explainability import TrustScoreExplainer

_explainer_instance = None

def get_prediction_and_explanation(payload):
    global _explainer_instance
    if _explainer_instance is None:
        _explainer_instance = TrustScoreExplainer()
    return _explainer_instance.explain_score(payload)