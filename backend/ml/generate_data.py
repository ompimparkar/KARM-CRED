"""
KARM CRED - synthetic dataset generator (v2: decomposed volatility).

Why this exists (read before touching):
The problem statement's "twist" is that *healthy* income variability (normal for
gig work - festival demand, weekend surges, monsoon dips shared by every rider
in a cohort) must be separated from *risky* variability (a real default signal:
a thin income floor, a declining trend, unpredictable gaps between paydays).

Structure of this generator (structural / latent model, NOT a hand-written
formula the model later reverse-engineers):

  1. Each worker draws latent roots:
       - floor_strength   -> how well their worst weeks hold up
       - trend slope      -> growing / flat / declining earnings
       - punctuality      -> on-time bill/payment behaviour
       - engagement       -> savings, tenure, transaction activity
       - gap discipline   -> predictability of gaps between earning days
       - excess volatility-> income swings *unusual for their cohort*
       (cohort-shared seasonality is a SEPARATE, label-free component)

  2. A 52-week income series is simulated FROM those roots, with:
       - a seasonal component shared by the worker's cohort
         (gig-type festival spikes / monsoon dips, city demand wiggle,
          weekday vs weekend demand in the daily layer)
       - an individual trend component
       - individual noise on top
       - weekly shock dips (bounded by floor_strength)
       - an earning-day activity process (drives gap irregularity AND
         partially the weekly income via missed days)

  3. Model features are MEASURED from the realized series (noisy estimates):
       cohort_adjusted_volatility, income_trend_slope, income_floor_ratio,
       earning_gap_irregularity ...

  4. trust_score is derived from the latent roots + independent Gaussian
       noise - i.e. from ground truth the model never sees. The observed
       features are noisy proxies of the roots, so R^2 will be realistically
       < 1 (expected ~0.6-0.85). The label:
         - rewards a strong income floor and a non-negative trend,
         - rewards on-time behaviour / engagement / gap regularity,
         - penalizes ONLY cohort-unusual volatility (cohort-shared
           variability - festival/monsoon/weekend lumpiness - is never
           penalized; raw CV is not even a model input),
         - carries independent noise so it is not a recombination of inputs.

  5. Two hand-built "twin" workers (TWIN_HEALTHY / TWIN_RISKY) are appended:
       identical average income, identical raw volatility (exactly), same
       cohort, same behavioural features - but healthy vs risky *patterns*
       (floor / trend / gaps). Used by ml/validate_twins.py as the
       judge-facing proof that the model scores patterns, not lumpiness.

Output: backend/data/karm_cred_synthetic_data.csv (single source of truth
consumed by app.py and train_model.py).
"""

import os

import numpy as np
import pandas as pd

SEED = 42
N_WORKERS = 5000
N_WEEKS = 52
N_DAYS = N_WEEKS * 7  # 364 days of daily activity

np.random.seed(SEED)
rng = np.random.default_rng(SEED)

# ---------------------------------------------------------------------------
# Cohort definitions (a cohort = same gig category / city / vehicle type).
# The whole point: variability normal for *this* cohort is healthy.
# ---------------------------------------------------------------------------
GIG_TYPES = ["food_delivery", "ride_hailing", "courier", "home_services"]
CITIES = ["delhi", "mumbai", "bengaluru", "pune", "hyderabad"]
VEHICLE_MIX = {
    "food_delivery": [("bike", 0.50), ("scooter", 0.45), ("car", 0.05)],
    "ride_hailing": [("car", 0.85), ("bike", 0.10), ("scooter", 0.05)],
    "courier": [("bike", 0.40), ("scooter", 0.45), ("car", 0.15)],
    "home_services": [("none", 0.70), ("car", 0.20), ("bike", 0.10)],
}

# Day-of-week demand (Sunday=0). Lives in the daily layer: weekend-heavy vs
# weekday-heavy gig work. Aggregated into the weekly series below.
DOW_DEMAND = {
    "food_delivery": [1.25, 0.92, 0.92, 0.98, 1.00, 1.10, 1.22],
    "ride_hailing": [1.20, 0.90, 0.92, 0.96, 1.02, 1.15, 1.25],
    "courier": [0.75, 1.15, 1.15, 1.15, 1.15, 1.10, 0.85],
    "home_services": [0.80, 1.15, 1.15, 1.15, 1.10, 1.05, 0.90],
}

FESTIVAL_WINDOWS = [(10, 12), (43, 45)]  # ~Holi, ~Diwali season (week index)
MONSOON_WEEKS = (21, 31)


def gig_seasonality(gig_type: str) -> np.ndarray:
    """Cohort-shared yearly profile: festival spikes + monsoon dips, mean 1."""
    s = np.ones(N_WEEKS)
    if gig_type == "home_services":
        # Pre-festival cleaning/repair rush, then a dip while people travel.
        for a, b in FESTIVAL_WINDOWS:
            pre = min(3, a)
            s[a - pre : a + 1] *= np.linspace(1.0, 1.30, pre + 1)
            s[a + 1 : b + 1] *= 0.85
        s[MONSOON_WEEKS[0] : MONSOON_WEEKS[1]] *= 1.05
    else:
        fest_peak = {"food_delivery": 1.45, "ride_hailing": 1.40,
                     "courier": 1.25}[gig_type]
        monsoon_dip = {"food_delivery": 0.80, "ride_hailing": 0.90,
                       "courier": 0.85}[gig_type]
        for a, b in FESTIVAL_WINDOWS:
            s[a : b + 1] *= np.linspace(1.0, fest_peak, b - a + 1)
        s[MONSOON_WEEKS[0] : MONSOON_WEEKS[1]] *= monsoon_dip
    return s / s.mean()


# City-level demand wiggle (shared by every gig worker in that city).
city_wiggle = {}
for _city in CITIES:
    _walk = np.cumsum(rng.normal(0.0, 0.006, N_WEEKS))
    city_wiggle[_city] = 1.0 + (_walk - _walk.mean())


def sample_roots() -> dict:
    """Draw the latent drivers for one worker (ground truth, never a feature)."""
    cat = rng.choice(["declining", "flat", "growing"], p=[0.25, 0.50, 0.25])
    if cat == "declining":
        slope = rng.uniform(-0.020, -0.004)      # fractional change / week
    elif cat == "growing":
        slope = rng.uniform(0.003, 0.015)
    else:
        slope = rng.uniform(-0.002, 0.002)
    return {
        "floor_root": rng.beta(2.0, 2.0),         # worst-week resilience
        "trend_slope": slope,
        "punctuality": 0.5 + 0.5 * rng.beta(2.0, 2.0),
        "engagement": rng.beta(2.0, 2.0),
        "gap_root": rng.beta(2.0, 2.0),           # gap predictability
        "excess_vol": float(min(rng.exponential(0.045), 0.12)),
    }


def simulate_activity(gap_root: float) -> np.ndarray:
    """
    Daily earning-day activity for 364 days.

    Regular workers rest randomly and rarely disappear. Irregular workers
    vanish unpredictably for multi-day stretches - this is what makes
    earning_gap_irregularity (std of gaps between earning days) a risk signal
    while raw amplitude (cohort seasonality) is not.
    """
    p_rest = 0.06 + 0.07 * (1.0 - gap_root)
    active = rng.random(N_DAYS) >= p_rest
    n_events = rng.poisson((0.015 + 0.11 * (1.0 - gap_root)) * N_WEEKS)
    for _ in range(int(n_events)):
        start = int(rng.integers(0, N_DAYS - 2))
        length = 2 + int(rng.geometric(0.35))  # mean ~5 days off the grid
        active[start : min(start + length, N_DAYS)] = False
    return active


def gap_irregularity(active: np.ndarray) -> float:
    """Std-dev (days) of gaps between consecutive income-generating days."""
    idx = np.flatnonzero(active)
    gaps = np.diff(idx)
    if gaps.size < 2:
        return 10.0  # essentially never earns -> maximal irregularity
    return float(gaps.std(ddof=0))


def simulate_weekly_income(roots: dict, gig_type: str, city: str,
                           cohort_idio: float, active: np.ndarray) -> np.ndarray:
    """Build the 52-week income series FROM the roots (see module docstring)."""
    level = rng.uniform(12000, 60000) * 12.0 / N_WEEKS  # weekly base level
    weeks = np.arange(N_WEEKS, dtype=float)
    centered = weeks - (N_WEEKS - 1) / 2.0
    trend = 1.0 + roots["trend_slope"] * centered

    # Weekly noise: cohort-shared base idiosyncrasy + individual excess.
    idio_sigma = np.sqrt(cohort_idio ** 2 + roots["excess_vol"] ** 2)
    noise = rng.normal(0.0, idio_sigma, N_WEEKS)

    # Weekly shock dips (platform ban, illness, phone broken...). Depth is
    # bounded by floor_strength: resilient workers barely drop.
    dips = np.ones(N_WEEKS)
    bad = rng.random(N_WEEKS) < 0.08
    depth = (0.08 + 0.72 * roots["floor_root"]) * rng.uniform(0.85, 1.15, bad.sum())
    dips[bad] = np.clip(depth, 0.05, 1.0)

    # Missed earning days soften income but don't zero it out (dow-weighted:
    # missing a weekend hurts a food-delivery rider more than a Tuesday).
    dow = np.asarray(DOW_DEMAND[gig_type])
    active_2d = active.reshape(N_WEEKS, 7)
    activity_frac = (active_2d * dow).sum(axis=1) / dow.sum()
    activity_effect = 0.55 + 0.45 * activity_frac

    seasonal = gig_seasonality(gig_type) * city_wiggle[city]
    weekly = level * seasonal * trend * (1.0 + noise) * dips * activity_effect
    return np.maximum(weekly, 1e-6)


def measure_features(weekly: np.ndarray, active: np.ndarray) -> dict:
    """Noisy observed features measured from the realized series."""
    weeks = np.arange(N_WEEKS, dtype=float)
    mean = weekly.mean()
    cw = weeks - weeks.mean()
    slope = float((cw * (weekly - mean)).sum() / (cw * cw).sum())
    return {
        "monthly_avg_income": mean * N_WEEKS / 12.0,
        "raw_income_cv": float(weekly.std(ddof=0) / mean),   # DIAGNOSTIC only
        "income_trend_slope": slope / mean,                  # fractional/week
        "income_floor_ratio": float(weekly.min() / np.median(weekly)),
        "earning_gap_irregularity": gap_irregularity(active),
    }


def repayment_capacity(roots: dict, noise: float) -> float:
    """
    Latent repayment capacity - the label's ground truth.

    Deliberately built from LATENT ROOTS (not the observed feature columns):
    the model only ever sees noisy proxies of these, so it cannot memorize a
    formula. Rewards floor + non-negative trend + punctuality + engagement +
    gap regularity; penalizes only cohort-EXCESS volatility; independent noise.
    """
    trend_score = float(np.clip(roots["trend_slope"] / 0.012, -1.0, 1.0))
    excess_norm = float(np.clip(roots["excess_vol"] / 0.12, 0.0, 1.0))
    return (
        0.30 * roots["floor_root"]          # rewards a strong income floor
        + 0.20 * trend_score                # penalizes a sustained decline
        + 0.25 * roots["punctuality"]       # on-time payment behaviour
        + 0.15 * roots["engagement"]        # savings / tenure / activity
        + 0.10 * roots["gap_root"]          # penalizes erratic payday gaps
        - 0.04 * excess_norm                # MILD penalty for volatility that
        #                                     is unusual for the cohort only
        # NOTE: no term for raw variability / cohort-shared seasonality.
        # The mildness is deliberate & tested: fairness_check.py asserts the
        # model never scores otherwise-healthy high-volatility workers more
        # than 30 points below their smooth-cohort peers.
        + noise                             # independent label noise
    )


def capacity_to_score(capacity: float) -> int:
    return int(np.clip(300 + 600 * (capacity - 0.10) / 0.80, 300, 900))


# ---------------------------------------------------------------------------
# Main population
# ---------------------------------------------------------------------------
cohort_idio_map = {}  # noise floor SHARED by everyone in a cohort
records = []
for i in range(N_WORKERS):
    gig = str(rng.choice(GIG_TYPES, p=[0.35, 0.30, 0.20, 0.15]))
    city = str(rng.choice(CITIES))
    vehicles, probs = zip(*VEHICLE_MIX[gig])
    vehicle = str(rng.choice(vehicles, p=probs))
    cohort_key_i = f"{gig}|{city}|{vehicle}"
    if cohort_key_i not in cohort_idio_map:
        cohort_idio_map[cohort_key_i] = float(rng.uniform(0.03, 0.055))
    cohort_idio = cohort_idio_map[cohort_key_i]

    roots = sample_roots()
    active = simulate_activity(roots["gap_root"])
    weekly = simulate_weekly_income(roots, gig, city, cohort_idio, active)
    feats = measure_features(weekly, active)

    # --- other observed signals (noisy views of punctuality/engagement) ----
    utility = float(np.clip(roots["punctuality"] + rng.normal(0, 0.07), 0.5, 1.0))
    upi = int(np.clip(rng.poisson(
        15 + 60 * roots["engagement"] + 0.0010 * feats["monthly_avg_income"]
        + 30 * roots["punctuality"]), 15, 250))
    tenure = int(np.clip(
        2 + 55 * (0.6 * roots["engagement"] + 0.4 * rng.beta(1.5, 3.0)), 2, 60))
    savings = float(np.clip(
        0.02 + 0.38 * np.clip(
            0.5 * roots["engagement"] + 0.4 * roots["punctuality"]
            + 0.1 * roots["floor_root"] + rng.normal(0, 0.06), 0, 1),
        0.02, 0.40))
    rating = float(np.clip(
        1 + 4 * np.clip(
            0.45 * roots["punctuality"] + 0.30 * roots["engagement"]
            + 0.25 * roots["floor_root"], 0, 1) + rng.normal(0, 0.28), 1.0, 5.0))
    rating_trend = float(np.clip(
        0.45 * float(np.clip(roots["trend_slope"] / 0.012, -1, 1))
        + rng.normal(0, 0.10), -0.8, 0.8))

    # --- partial data: the "invisible worker" edge case -------------------
    # Short-tenure renters often have no formal utility bill; cash/wallet
    # workers may have no UPI history; new workers may have no linked
    # platform profile. Missing -> NaN in CSV; imputed later (cohort median
    # + data_completeness indicator), never silently ignored.
    miss_utility = rng.random() < (0.06 + 0.14 * (1 - tenure / 60))
    miss_upi = rng.random() < (0.04 + 0.12 * (1 - roots["engagement"]))
    miss_platform = rng.random() < (0.05 + 0.10 * (1 - min(tenure / 12, 1)))
    if miss_utility:
        utility = np.nan
    if miss_upi:
        upi = np.nan
    if miss_platform:
        rating = np.nan
        rating_trend = np.nan
    completeness = round(
        ((not miss_utility) + (not miss_upi) + (not miss_platform)) / 3.0, 4)

    capacity = repayment_capacity(roots, float(rng.normal(0, 0.08)))

    records.append({
        "user_id": f"GIG_{i + 1:04d}",
        "gig_type": gig,
        "city": city,
        "vehicle_class": vehicle,
        "monthly_avg_income": round(feats["monthly_avg_income"], 2),
        "raw_income_cv": round(feats["raw_income_cv"], 4),
        "income_trend_slope": round(feats["income_trend_slope"], 5),
        "income_floor_ratio": round(feats["income_floor_ratio"], 4),
        "earning_gap_irregularity": round(feats["earning_gap_irregularity"], 4),
        "upi_monthly_txns": None if np.isnan(upi) else int(upi),
        "utility_on_time_ratio": None if np.isnan(utility) else round(utility, 4),
        "platform_tenure_months": tenure,
        "avg_savings_ratio": round(savings, 4),
        "avg_platform_rating": None if np.isnan(rating) else round(rating, 2),
        "rating_trend": None if np.isnan(rating_trend) else round(rating_trend, 3),
        "data_completeness": completeness,
        "trust_score": capacity_to_score(capacity),
        # debug-only roots (dropped before CSV write; used by the report below)
        "_root_floor": roots["floor_root"],
        "_root_trend": roots["trend_slope"],
        "_root_gap": roots["gap_root"],
        "_root_excess": roots["excess_vol"],
    })

df = pd.DataFrame(records)

# ---------------------------------------------------------------------------
# cohort_adjusted_volatility: worker CV minus median CV of their cohort
# (same gig / city / vehicle). Isolates variability that is UNUSUAL for the
# line of work - festival/monsoon/weekend lumpiness shared by peers drops out.
# ---------------------------------------------------------------------------
cohort_key = df["gig_type"] + "|" + df["city"] + "|" + df["vehicle_class"]
cohort_median_cv = df.groupby(cohort_key)["raw_income_cv"].transform("median")
df["cohort_adjusted_volatility"] = (
    df["raw_income_cv"] - cohort_median_cv).round(5)

# Correlations of measured features with their latent roots (report-only;
# computed before the debug root columns are dropped for the CSV).
ROOT_PAIRS = [
    ("income_floor_ratio", "_root_floor", "floor <-> floor root"),
    ("income_trend_slope", "_root_trend", "trend <-> trend root"),
    ("earning_gap_irregularity", "_root_gap", "gaps <-> gap root (expect -)"),
    ("cohort_adjusted_volatility", "_root_excess",
     "coh-adj vol <-> excess (expect +)"),
]
root_corrs = {note: df[f].corr(df[r]) for f, r, note in ROOT_PAIRS}
near_zero_floor_n = int((df["income_floor_ratio"] < 0.01).sum())

# ---------------------------------------------------------------------------
# The twin pair: judge-facing proof fixture (see module docstring + validate_twins.py)
# ---------------------------------------------------------------------------
def build_twin_pair(shared_cohort_median_cv: float) -> list:
    twin_rng = np.random.default_rng(7)
    weeks = np.arange(N_WEEKS, dtype=float)
    centered = weeks - (N_WEEKS - 1) / 2.0
    cv_target = 0.38                       # shared raw volatility of BOTH twins
    mu_weekly = 30000.0 * 12.0 / N_WEEKS     # both earn exactly 30,000/month
    sigma_weekly = cv_target * mu_weekly     # both have identical raw CV
    season = gig_seasonality("food_delivery")

    # --- healthy: solid base, festival UP-spikes, flat trend, soft dips ----
    # High amplitude (high CV) but the floor holds: worst week ~70% of median.
    healthy = season.copy()
    healthy[[10, 11, 12, 43, 44, 45]] *= 1.70   # big but UPSIDE swings
    healthy *= 1.0 + twin_rng.normal(0, 0.04, N_WEEKS)
    healthy[[5, 17, 33]] *= 0.88                 # occasional soft weeks
    healthy *= 1.0 + 0.001 * centered            # flat (slightly growing)

    # --- risky: sustained decline + deep disappearance weeks ---------------
    risky = season * (1.0 - 0.015 * centered)
    risky *= 1.0 + twin_rng.normal(0, 0.06, N_WEEKS)
    bad = twin_rng.choice(N_WEEKS, size=7, replace=False)
    risky[bad] *= 0.13                            # worst week near zero

    # Force BOTH to identical mean and identical CV (exactly).
    def standardize(x):
        return mu_weekly + sigma_weekly * (x - x.mean()) / x.std(ddof=0)

    healthy, risky = standardize(healthy), standardize(risky)
    assert healthy.min() > 0 and risky.min() > 0, "twin weekly income must stay positive"

    def activity(pattern_seed, p_rest, n_events, mean_len):
        r = np.random.default_rng(pattern_seed)
        act = r.random(N_DAYS) >= p_rest
        for _ in range(n_events):
            start = int(r.integers(0, N_DAYS - 8))
            act[start : start + 2 + int(r.geometric(1.0 / mean_len))] = False
        return act

    act_h = activity(11, 0.06, 1, 3)              # predictable: gaps ~0.5 sd
    act_r = activity(12, 0.18, 8, 7)              # disappears unpredictably

    def rows(active, tag, floor_root, gap_root, rating_trend_v):
        series = healthy if tag == "healthy" else risky
        feats = measure_features(series, active)
        return {
            "user_id": f"TWIN_{tag.upper()}",
            "gig_type": "food_delivery",
            "city": "delhi",
            "vehicle_class": "bike",
            "monthly_avg_income": round(feats["monthly_avg_income"], 2),
            "raw_income_cv": round(feats["raw_income_cv"], 6),
            "income_trend_slope": round(feats["income_trend_slope"], 5),
            "income_floor_ratio": round(feats["income_floor_ratio"], 4),
            "earning_gap_irregularity": round(feats["earning_gap_irregularity"], 4),
            "upi_monthly_txns": 110,               # identical behavioural data
            "utility_on_time_ratio": 0.90,
            "platform_tenure_months": 26,
            "avg_savings_ratio": 0.17,
            "avg_platform_rating": 4.55,
            "rating_trend": rating_trend_v,        # only pattern-linked field
            "data_completeness": 1.0,
            "trust_score": capacity_to_score(repayment_capacity({
                "floor_root": floor_root,
                "trend_slope": 0.001 if tag == "healthy" else -0.015,
                "punctuality": 0.90,
                "engagement": 0.55,
                "gap_root": gap_root,
                "excess_vol": 0.04,                # same for both: same raw CV
            }, float(np.random.default_rng(99 if tag == "healthy"  else 100).normal(0, 0.08)))),
            "_cohort_median_cv": shared_cohort_median_cv,
        }

    return [rows(act_h, "healthy", 0.80, 0.92, 0.12),
            rows(act_r, "risky", 0.12, 0.15, -0.22)]


# Compute cohort medians from the main population, then append the twins.
twins = build_twin_pair(float(
    cohort_median_cv[cohort_key == "food_delivery|delhi|bike"].median()))
twin_df = pd.DataFrame(twins)
twin_df["cohort_adjusted_volatility"] = (
    twin_df["raw_income_cv"] - twin_df.pop("_cohort_median_cv")).round(5)

# --- fixture guarantees (these ARE the demo) -------------------------------
h, r = twin_df.iloc[0], twin_df.iloc[1]
assert abs(h["monthly_avg_income"] - r["monthly_avg_income"]) < 0.01, \
    "twins must have identical average income"
assert abs(h["raw_income_cv"] - r["raw_income_cv"]) < 1e-9, \
    "twins must have identical raw volatility"
assert abs(h["cohort_adjusted_volatility"] - r["cohort_adjusted_volatility"]) < 1e-9, \
    "twins must have identical peer-adjusted volatility"
assert h["income_floor_ratio"] >= 0.55, "healthy twin must keep a strong floor"
assert r["income_floor_ratio"] <= 0.25, "risky twin must have a thin floor"
assert h["income_trend_slope"] >= -0.003, "healthy twin must be flat/rising"
assert r["income_trend_slope"] <= -0.009, "risky twin must be declining"
assert h["earning_gap_irregularity"] <= 1.5, "healthy twin gaps must be regular"
assert r["earning_gap_irregularity"] >= 2.0, "risky twin gaps must be erratic"
assert r["earning_gap_irregularity"] >= 2 * h["earning_gap_irregularity"]
assert h["trust_score"] - r["trust_score"] >= 100, \
    "ground-truth label must separate the twins by 100+ points"

df = pd.concat([df, twin_df], ignore_index=True)

# Column order = CSV contract (see MODEL_CARD.md)
df = df[[
    "user_id", "gig_type", "city", "vehicle_class",
    "monthly_avg_income", "raw_income_cv", "cohort_adjusted_volatility",
    "income_trend_slope", "income_floor_ratio", "earning_gap_irregularity",
    "upi_monthly_txns", "utility_on_time_ratio", "platform_tenure_months",
    "avg_savings_ratio", "avg_platform_rating", "rating_trend",
    "data_completeness", "trust_score",
]]

output_dir = os.path.join(os.path.dirname(__file__), "..", "data")
os.makedirs(output_dir, exist_ok=True)
output_path = os.path.abspath(os.path.join(
    output_dir, "karm_cred_synthetic_data.csv"))
df.to_csv(output_path, index=False)

# ---------------------------------------------------------------------------
# Console report (screenshot-able for judges)
# ---------------------------------------------------------------------------
print(f"Generated dataset: {output_path}  ({len(df)} rows)")
print("\nLabel (trust_score) percentiles:")
print(df["trust_score"].describe(percentiles=[.1, .25, .5, .75, .9])
      .round(1).to_string())

print("\nFeature spread (model inputs):")
for col in ["monthly_avg_income", "cohort_adjusted_volatility",
            "income_trend_slope", "income_floor_ratio",
            "earning_gap_irregularity", "avg_platform_rating",
            "rating_trend", "data_completeness"]:
    s = df[col]
    print(f"  {col:28s} min={s.min():9.3f}  p50={s.median():9.3f}  "
          f"max={s.max():9.3f}  missing={int(s.isna().sum())}")

print("\nPartial-data (invisible-worker) rows:",
      f"{int((df['data_completeness'] < 1.0).sum())} "
      f"({(df['data_completeness'] < 1.0).mean():.0%})")

print("\nMeasured feature <-> latent root correlation (learnability check):")
for note, corr_val in root_corrs.items():
    print(f"  {note:36s} r = {corr_val:+.3f}")
print(f"  near-zero floor rows (<0.01): {near_zero_floor_n}")

print("\nTWIN FIXTURE (identical income + identical volatility):")
cols = ["monthly_avg_income", "raw_income_cv", "cohort_adjusted_volatility",
        "income_floor_ratio", "income_trend_slope",
        "earning_gap_irregularity", "trust_score"]
print(twin_df.set_index("user_id")[cols].round(4).to_string())
print("\nOK: fixture assertions passed "
      "(healthy twin label leads by "
      f"{int(h['trust_score']) - int(r['trust_score'])} pts).")
