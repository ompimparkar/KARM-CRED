import os
import joblib
import pandas as pd
import shap

MODEL_PATH = os.path.join(os.path.dirname(__file__), 'saved_models', 'lgbm_model.pkl')

class TrustScoreExplainer:
    def __init__(self):
        self.model = joblib.load(MODEL_PATH)
        self.explainer = shap.TreeExplainer(self.model)
        self.feature_names = [
            'monthly_avg_income', 
            'income_volatility', 
            'upi_monthly_txns', 
            'utility_on_time_ratio', 
            'platform_tenure_months', 
            'avg_savings_ratio'
        ]

    def explain_score(self, user_features):
        df_input = pd.DataFrame([user_features])[self.feature_names]
        
        predicted_score = int(self.model.predict(df_input)[0])
        shap_values = self.explainer.shap_values(df_input)[0]
        
        labels = {
            'utility_on_time_ratio': 'Utility Bill On-Time Ratio',
            'avg_savings_ratio': 'Monthly Savings Ratio',
            'upi_monthly_txns': 'UPI Transaction Velocity',
            'platform_tenure_months': 'Gig Platform Experience',
            'income_volatility': 'Income Fluctuation Factor',
            'monthly_avg_income': 'Average Monthly Earnings'
        }

        reasons = []
        for feat, val in zip(self.feature_names, shap_values):
            impact = round(float(val), 1)
            reasons.append({
                "feature": labels.get(feat, feat),
                "impact_points": impact,
                "type": "positive" if impact >= 0 else "negative"
            })

        reasons = sorted(reasons, key=lambda x: abs(x['impact_points']), reverse=True)

        return {
            "predicted_trust_score": max(300, min(900, predicted_score)),
            "reasons": reasons
        }