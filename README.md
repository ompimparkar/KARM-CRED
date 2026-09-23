# KARM-CRED
Alternative credit scoring platform for the gig economy - CODEX 2026".
# KARM CRED 🚀
**Credit Score for the Invisible**

## 📌 Problem Statement
Millions of gig workers and freelancers are entirely invisible to traditional credit bureaus due to volatile income streams and a lack of formal credit history. When applying for loans or financial services, they are often flagged as high-risk, despite having healthy financial habits, utility payment histories, and consistent UPI transaction volumes.

**The twist we actually solve:** not all income volatility is the same. A food-delivery rider whose income spikes during festivals and dips during monsoon is *normal for the cohort* — that is healthy variability. A rider whose worst week collapses to near-zero, whose earnings trend is declining, and who disappears unpredictably between paydays is a real risk signal. KARM CRED separates the two instead of applying a flat volatility penalty.

## 💡 The Solution: KARM CRED
KARM CRED is an alternative credit scoring platform designed specifically for the gig economy. By securely analyzing on-device data (SMS, UPI transaction frequency, and utility bill payments) **only from sources the user explicitly consents to**, KARM CRED generates a fair and dynamic **TrustScore (300-900)**.

We don't just provide a score; we provide transparency. Our AI engine explains exactly *why* a score was given — in plain language, with counterfactual suggestions ("increasing your utility on-time ratio to 90% would add ~+35 points") — empowering users to understand and improve their financial health.

## 🧠 How the "twist" is solved (decomposed volatility)
Instead of one `income_volatility` scalar, each synthetic worker gets a **simulated 52-week income series** (seasonal cohort components + individual trend + noise + weekly shocks + a daily earning-activity process), from which we *measure*:

| Feature | Plain meaning |
|---|---|
| `cohort_adjusted_volatility` | Worker's CV **minus** the median CV of peers (same gig/city/vehicle). Only swings that are unusual *for their line of work* remain. |
| `income_trend_slope` | OLS slope of weekly income. Flat/growing = healthy; sustained decline = risk. |
| `income_floor_ratio` | `min(weekly)/median(weekly)`. A worst week at 60% of median ≠ a worst week near zero. |
| `earning_gap_irregularity` | Std-dev of gaps (days) between income-generating days. Unpredictable disappearances ≠ a predictable weekly off day. |
| `raw_income_cv` | Kept as a **diagnostic column only** — never a model input. |

The label is generated from a **latent repayment capacity** (income floor + trend + on-time behaviour + engagement + gap regularity − a *mild* penalty for cohort-excess volatility) plus independent noise — **not** a recombination of the input columns. It never penalizes raw or cohort-shared variability.

**The proof (screenshot this):** `TWIN_HEALTHY` vs `TWIN_RISKY` — identical average income (₹30,000), identical raw volatility (CV = 0.38), identical peer-adjusted volatility, identical behavioural data — but a stable floor/rising trend/regular paydays vs a thin floor/declining trend/erratic gaps.

```
backend$ python ml/validate_twins.py
[PASS] fixture: income, raw CV and cohort-adjusted CV are identical
[PASS] model gap = 283 points (healthy 685 vs risky 402)
```

## 🛠️ Proposed Tech Stack
*   **Frontend (Mobile App):** Android / Kotlin (on-device SMS/API data simulation, per-source consent screen, dashboard with SHAP reason cards).
*   **Backend API:** Python / Flask (consent gate, validation, model predictions; in-memory SQLite with parameterized queries).
*   **Machine Learning Engine:** LightGBM trained on the decomposed feature set (see `MODEL_CARD.md`).
*   **Explainable AI (XAI):** SHAP reason cards + plain-English one-line summary + counterfactual suggestions.

## ⚙️ Architecture Workflow
1.  **Consent:** The app's onboarding screen names each data source (UPI/bank SMS, utility bills, platform rating) and lets the user grant/deny per source. Scoring is blocked with zero grants; the backend nulls any non-consented source before the model sees it (`?consent=upi,utility,platform`).
2.  **Data Extraction:** The mobile app reads only consented, local transactional data.
3.  **Risk Engine Processing:** Routed via the Flask API (validated, imputed per the missing-data policy) to the LightGBM model.
4.  **Score Generation & Explanation:** TrustScore + SHAP reason codes + summary line + counterfactuals.
5.  **Dashboard Display:** Score and actionable insights on mobile or web dashboard.

## ▶️ How to run (backend)
```bash
cd backend
pip install -r requirements.txt

python ml/generate_data.py     # simulate 52-week series -> data/*.csv (5002 rows incl. twins)
python ml/train_model.py       # train + print R^2 note + run fairness check (fails if bias found)
python ml/validate_twins.py    # judge-facing proof: identical volatility, 283-point gap
python ml/fairness_check.py    # standalone re-run of the fairness table

set FLASK_DEBUG=0              # default; debug mode is OFF (see Security)
python app.py                  # http://localhost:5000
```

Try `GIG_0001` … `GIG_5000`, plus the fixtures **`TWIN_HEALTHY`** and **`TWIN_RISKY`** on the dashboard.

## 📊 Model quality: what R² to expect (and why)
The label comes from latent capacity + independent noise, so the model cannot memorize a formula. **R² lands around 0.65** (RMSE ≈ 67 points) — this is the *healthy* range (0.6–0.85) for a realistically-noisy label. An R² ≈ 0.99 would mean the target is a recombination of the inputs (circular), which is exactly what we removed. Current run: `Model R2: 0.66`.

## ⚖️ Fairness check (the bias the problem statement warns about)
`python ml/train_model.py` buckets the held-out set into deciles of `cohort_adjusted_volatility` and compares *otherwise-healthy* workers (good floor, non-negative trend, reliable utility payments) across deciles. Result: **PASS** — healthy D10 workers score ~50 points *above* healthy D1, i.e. the model does not systematically punish naturally/unusually volatile workers. Full table printed at training time and recorded in `MODEL_CARD.md`.

## 🔒 Security
*   **Flask debug mode is OFF by default.** Debug exposes an RCE console — only enable locally with `FLASK_DEBUG=1`, never during a demo or on a network (the app prints a warning if you do).
*   **CORS is restricted** via the `ALLOWED_ORIGINS` env var (default: `http://localhost:5000,http://127.0.0.1:5000`), not `CORS(app)` open-to-all.
*   **No hardcoded LAN IP in the Android app.** The backend URL is `BuildConfig.API_BASE_URL`, overridden with `-PKARM_API_BASE_URL="https://<lan-ip>:5000/"` (default `http://10.0.2.2:5000/` for the emulator).
*   **HTTPS for demos:** run the API with `KARM_HTTPS=1 python app.py` (uses an ad-hoc certificate via pyopenssl) and build the app against that URL. Production must be HTTPS — cleartext is a demo convenience only.
*   **Input validation:** `/api/user/<id>` validates the row and every required field (404 with sample IDs, 422 with the missing-field list) and queries SQLite with bound parameters.

## 🛡️ Privacy, consent & compliance (what we implement vs. what production needs)
**Implemented now**
*   A real per-source consent screen in the app (`OnboardingActivity`): each source is named, granted or denied individually, revocable later from the dashboard ("Manage Data Sources").
*   Server-side enforcement: non-consented sources are nulled **before** the model — not just hidden in the UI. Scores reflect honest `data_completeness` instead of pretending a partial file is complete.
*   On-device parsing claim is scoped honestly: this demo *simulates* SMS parsing; no chat data handling exists at all.

**The real compliance path (named, not buzzworded)**
*   **DPDP Act (India):** consent must be free, specific, informed, unconditional and withdrawable; purpose limitation is why consent is per source, not one "I agree" button.
*   **Account Aggregator (AA) framework:** the RBI-regulated, consent-based data-sharing rail is the realistic production path for UPI/bank data (consent artefacts, purpose-bound sharing, auditable revocation) — instead of reading raw SMS. We name it because that is where this product would plug in; implementing AA membership is out of scope for a hackathon.
*   **RBI digital lending guidance:** fairness/transparency expectations for algorithmic credit decisions are the reason explainability (SHAP reason cards + counterfactuals) and the fairness check are first-class artifacts here, not decorations.

## 🗂️ Model documentation
See **[`MODEL_CARD.md`](MODEL_CARD.md)** — features and meanings, label construction, missing-data policy, fairness results, twin validation, limitations.

## 👥 Team Quad Core
*   **Om** - Team Leader & AI/Backend Architecture
*   **Tanmay** - Mobile Frontend Development
*   **Aryan** - Mobile Frontend Development
*   **Atharva** - Mobile Frontend Development

## 📦 Production deploy

- `backend/requirements.txt` ships **gunicorn** alongside Flask. Two `Procfile`s are provided: the root one (`gunicorn --chdir backend app:app`) for platforms that deploy from the repo root, and `backend/Procfile` (`gunicorn app:app`) for platforms whose root directory is set to `backend/`.
- `.env.example` documents every environment knob: `ALLOWED_ORIGINS` (CORS allow-list — add your LAN IP when opening the dashboard from another device), `FLASK_DEBUG` (local only — never expose the debug console), `KARM_HTTPS`, `KARM_HOST`, `KARM_PORT`.
- `GET /api/health` returns `{"status": "ok", "cache_built": true|false}` — `cache_built` tells you whether the population views have been computed in this process yet (i.e. the instance is warm).
- ⚠️ **`_CACHE` is process-lifetime only.** The batch-scored population, leaderboard and fairness results live in a plain in-memory dict in `backend/app.py`; nothing is written to disk. Every restart/redeploy rebuilds them on first use (a few seconds for the first leaderboard/fairness hit, then cached for the life of that process). Scale horizontally with that in mind: each worker process owns its own cache.

## 🧪 Tests & CI

- `backend/tests/test_api.py` — pytest suite over all six endpoints plus the 404 (unknown user) and 422 (malformed `/api/simulate`) paths. Run from `backend/`: `python -m pytest tests -q`.
- `.github/workflows/ci.yml` runs on every push/PR: install → `ml/generate_data.py` → `ml/train_model.py` → `ml/validate_twins.py` → `ml/fairness_check.py` → `pytest tests`. Artifacts are regenerated from scratch in CI, so a stale committed model can never mask a regression.

## 📱 Android app (full 6-section parity)

`android/` mirrors all six web sections through a Material `BottomNavigationView` (Score, Leaderboard, Simulator, Compare, Fairness, Consent) on the same dark theme + cyan accent, reusing the existing `ApiService` and `ConsentManager`:

```bash
cd android
# Point at your machine's Flask server (emulator default is 10.0.2.2):
./gradlew assembleDebug -PKARM_API_BASE_URL="http://<your-lan-ip>:5000/"
```

First launch gates on the consent screen (grant each source or no scoring happens); the Consent section later lets you revoke and re-score live.

### ✨ Motion & polish layer (website-parity pass)

The Android app mirrors the web dashboard's production polish end to end:

* **Score gauge + count-up** — hand-drawn `ScoreGaugeView` (band-coloured arc, `progress = (score−300)/600`) driven by `Motion.countUpTo()` so the number counts up (~800 ms ease-out) with a green/amber/red crossfade, on My TrustScore, the Simulator, and both Compare cards.
* **Profile radar** — `RadarView` draws the 5-axis polygon growing from the centre (~900 ms) from the backend's `radar_values`.
* **SHAP reason bars** — reason, counterfactual, compare-diff, and leaderboard cards grow a normalised bar from 0 (green positive / red negative / cyan counterfactual, min 4 % width, same rule as the web).
* **Skeleton loaders** — one reusable `SkeletonView` (pulsing 0.35↔0.8 alpha, ~1.1 s) with per-screen patterns: SCORE, GRID6, CARD2, TALL2, FAIR, PREVIEW; replaces every "Loading…" text state, with error + retry (leaderboard) and empty-state art (shared `ic_empty_state`).
* **Trust-network background** — `NetworkBgView` particles on all six screens: 28/40/56 nodes by width, lines capped at 0.15 alpha and skipped under 480 dp, ~30 fps, paused off-screen, one static frame under reduced motion.
* **Page transitions & stagger** — shared fade/rise cross-fade (`fade_in`/`fade_out`) on every bottom-nav and deep-link navigation; RecyclerViews stagger in at i×39 ms (≈400 ms budget for a 10-card list).
* **Micro-interactions** — 0.97 press-scale on cards and buttons, decile bars growing from 0 on Fairness, consent chip flash-on/flash-off (cyan/red, 2 × 0.85 s) on every source toggle.
* **Share score card** — renders a dark 1080×1420 PNG (brand, id, band-coloured score, summary, ₹/metrics, footer) and opens the system share sheet via `FileProvider` — the Android twin of the web "Download PDF".
* **First-load guided tour** — 5 steps (bottom bar → search → twins demo → fairness stats → consent toggles) with spotlight overlay, Skip/Back/Next, `karmcred.tour.v1` preference, and **Replay the guided tour** on the Consent screen.
* Everything honours the system animator scale (`prefers-reduced-motion` equivalent): animations snap to final states when disabled.

---
*Developed for CODEX 2026 - MUSA*
