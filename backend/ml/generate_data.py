import os
import numpy as np
import pandas as pd

np.random.seed(42)
n_samples = 5000

data = {
    'user_id': [f"GIG_{i:04d}" for i in range(1, n_samples + 1)],
    'monthly_avg_income': np.random.uniform(12000, 60000, n_samples),
    'income_volatility': np.random.uniform(0.10, 0.65, n_samples),
    'upi_monthly_txns': np.random.randint(15, 250, n_samples),
    'utility_on_time_ratio': np.random.uniform(0.50, 1.00, n_samples),
    'platform_tenure_months': np.random.randint(2, 60, n_samples),
    'avg_savings_ratio': np.random.uniform(0.02, 0.40, n_samples)
}

df = pd.DataFrame(data)

score_raw = (
    0.35 * (df['utility_on_time_ratio'] * 100) +
    0.25 * (df['avg_savings_ratio'] * 100) +
    0.20 * np.minimum(df['upi_monthly_txns'] / 2.5, 100) +
    0.15 * np.minimum(df['platform_tenure_months'] * 2, 100) -
    0.05 * (df['income_volatility'] * 100)
)

df['trust_score'] = 300 + ((score_raw - score_raw.min()) / (score_raw.max() - score_raw.min())) * 600
df['trust_score'] = df['trust_score'].astype(int)

output_dir = os.path.join(os.path.dirname(__file__), '..', '..', 'data')
os.makedirs(output_dir, exist_ok=True)
output_path = os.path.join(output_dir, 'karm_cred_synthetic_data.csv')

df.to_csv(output_path, index=False)
print(f"✅ Generated dataset: {output_path}")