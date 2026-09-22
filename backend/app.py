from flask import Flask, render_template, jsonify, request
from flask_cors import CORS
import pandas as pd
import os
from ml.predict import get_prediction_and_explanation

app = Flask(__name__)
CORS(app)

DATA_PATH = os.path.join(os.path.dirname(__file__), 'data', 'karm_cred_synthetic_data.csv')
df = pd.read_csv(DATA_PATH)

# Clean column names (strip spaces and convert to lower case)
df.columns = df.columns.str.strip().str.lower()

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/user/<user_id>', methods=['GET'])
def get_user_data(user_id):
    # Normalize requested user_id (strip spaces & make uppercase)
    search_id = str(user_id).strip().upper()
    
    # Perform case-insensitive match on stripped user_id column
    user_row = df[df['user_id'].astype(str).str.strip().str.upper() == search_id]
    
    if user_row.empty:
        # Provide sample valid IDs in error response for easy debugging
        sample_ids = df['user_id'].dropna().astype(str).str.strip().head(3).tolist()
        return jsonify({
            "error": f"User ID '{user_id}' not found.",
            "sample_ids_in_csv": sample_ids
        }), 404
    
    user_data = user_row.iloc[0]
    
    payload = {
        'monthly_avg_income': float(user_data['monthly_avg_income']),
        'income_volatility': float(user_data['income_volatility']),
        'upi_monthly_txns': float(user_data['upi_monthly_txns']),
        'utility_on_time_ratio': float(user_data['utility_on_time_ratio']),
        'platform_tenure_months': float(user_data['platform_tenure_months']),
        'avg_savings_ratio': float(user_data['avg_savings_ratio'])
    }
    
    ml_result = get_prediction_and_explanation(payload)
    
    radar_values = [
        round((1 - payload['income_volatility']) * 100, 1),
        round(min(payload['upi_monthly_txns'] / 2.5, 100), 1),
        round(payload['utility_on_time_ratio'] * 100, 1),
        50.0,
        round(payload['avg_savings_ratio'] * 200, 1)
    ]
    
    return jsonify({
        "user_id": user_id,
        "features": payload,
        "predicted_trust_score": ml_result["predicted_trust_score"],
        "reasons": ml_result["reasons"],
        "radar_values": radar_values
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)