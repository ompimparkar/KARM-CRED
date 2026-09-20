from flask import Flask, request, jsonify, render_template
from ml.predict import get_prediction_and_explanation

app = Flask(__name__)

@app.route('/')
def home():
    return render_template('index.html')

@app.route('/api/score', methods=['POST'])
def calculate_score():
    data = request.get_json()
    
    required_keys = [
        'monthly_avg_income', 'income_volatility', 'upi_monthly_txns',
        'utility_on_time_ratio', 'platform_tenure_months', 'avg_savings_ratio'
    ]
    
    if not data or not all(k in data for k in required_keys):
        return jsonify({
            "status": "error",
            "message": f"Missing required fields. Required: {required_keys}"
        }), 400

    try:
        result = get_prediction_and_explanation(data)
        return jsonify({
            "status": "success",
            "data": result
        })
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)