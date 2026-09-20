import os
import joblib
import pandas as pd
import lightgbm as lgb
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error, r2_score

base_dir = os.path.dirname(__file__)
data_path = os.path.join(base_dir, '..', '..', 'data', 'karm_cred_synthetic_data.csv')
model_dir = os.path.join(base_dir, 'saved_models')

df = pd.read_csv(data_path)

features = [
    'monthly_avg_income', 
    'income_volatility', 
    'upi_monthly_txns', 
    'utility_on_time_ratio', 
    'platform_tenure_months', 
    'avg_savings_ratio'
]
X = df[features]
y = df['trust_score']

X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

model = lgb.LGBMRegressor(
    n_estimators=100,
    learning_rate=0.05,
    max_depth=5,
    random_state=42
)
model.fit(X_train, y_train)

y_pred = model.predict(X_test)
print(f"📊 Model R²: {r2_score(y_test, y_pred):.4f} | RMSE: {mean_squared_error(y_test, y_pred, squared=False):.2f}")

os.makedirs(model_dir, exist_ok=True)
model_path = os.path.join(model_dir, 'lgbm_model.pkl')
joblib.dump(model, model_path)
print(f"✅ Saved model artifact: {model_path}")