// 1. DOM Elements
const upiInput = document.getElementById('upi');
const utilityInput = document.getElementById('utility');

const upiVal = document.getElementById('upi-val');
const utilityVal = document.getElementById('utility-val');
const scoreDisplay = document.getElementById('score-display');
const summaryDisplay = document.getElementById('summary-display');
const reasonsList = document.getElementById('reasons-list');
const cfList = document.getElementById('cf-list');

const mFloor = document.getElementById('m-floor');
const mCohvol = document.getElementById('m-cohvol');
const mTrend = document.getElementById('m-trend');
const mGap = document.getElementById('m-gap');
const mRating = document.getElementById('m-rating');
const mCompleteness = document.getElementById('m-completeness');
const mCohort = document.getElementById('m-cohort');

// 2. Initialize Chart.js Radar Chart
// Axes mirror the response's radar_values:
// [Income Floor, UPI Activity, Utility Reliability, Platform Rating, Savings]
const ctx = document.getElementById('creditRadarChart').getContext('2d');
const radarChart = new Chart(ctx, {
    type: 'radar',
    data: {
        labels: ['Income Floor', 'UPI Activity', 'Utility Reliability', 'Platform Rating', 'Savings Discipline'],
        datasets: [
            {
                label: 'Traditional CIBIL',
                data: [10, 40, 50, 15, 20],
                backgroundColor: 'rgba(239, 68, 68, 0.2)',
                borderColor: '#ef4444',
                pointBackgroundColor: '#ef4444'
            },
            {
                label: 'KARM CRED',
                data: [50, 60, 85, 40, 75],
                backgroundColor: 'rgba(56, 189, 248, 0.2)',
                borderColor: '#38bdf8',
                pointBackgroundColor: '#38bdf8'
            }
        ]
    },
    options: {
        responsive: true,
        scales: {
            r: {
                angleLines: { color: '#334155' },
                grid: { color: '#334155' },
                pointLabels: { color: '#94a3b8' },
                ticks: { color: '#94a3b8', backdropColor: 'transparent' },
                suggestedMin: 0,
                suggestedMax: 100
            }
        }
    }
});

const has = (v) => v !== null && v !== undefined;

// 3. Fetch User Data from Backend by User ID
async function fetchUserData() {
    const userId = document.getElementById('userIdSearch').value.trim();
    if (!userId) {
        alert("Please enter a valid User ID (e.g. GIG_0001, or try TWIN_HEALTHY / TWIN_RISKY)");
        return;
    }

    try {
        const response = await fetch(`/api/user/${encodeURIComponent(userId)}`);
        if (!response.ok) {
            const err = await response.json().catch(() => ({}));
            alert(err.error || `User ID ${userId} not found! Try GIG_0001 to GIG_5000.`);
            return;
        }

        const data = await response.json();
        const f = data.features || {};

        // Trust score + one-line natural-language summary
        if (scoreDisplay) scoreDisplay.innerText = data.predicted_trust_score;
        if (summaryDisplay) summaryDisplay.innerText = data.summary || '';

        // Sliders (null-safe: partial profiles may lack a source)
        if (upiInput && upiVal) {
            if (has(f.upi_monthly_txns)) {
                upiInput.value = f.upi_monthly_txns;
                upiVal.innerText = Math.round(f.upi_monthly_txns);
            } else {
                upiVal.innerText = 'not available';
            }
        }
        if (utilityInput && utilityVal) {
            if (has(f.utility_on_time_ratio)) {
                utilityInput.value = f.utility_on_time_ratio;
                utilityVal.innerText = `${Math.round(f.utility_on_time_ratio * 100)}%`;
            } else {
                utilityVal.innerText = 'not available';
            }
        }

        // Metric chips
        if (mFloor) mFloor.innerText = has(f.income_floor_ratio)
            ? `${Math.round(f.income_floor_ratio * 100)}% of typical week` : '—';
        if (mCohvol) mCohvol.innerText = has(f.cohort_adjusted_volatility)
            ? `${f.cohort_adjusted_volatility >= 0 ? '+' : ''}${(f.cohort_adjusted_volatility * 100).toFixed(1)}% vs peers` : '—';
        if (mTrend) mTrend.innerText = has(f.income_trend_slope)
            ? `${f.income_trend_slope >= 0 ? '+' : ''}${(f.income_trend_slope * 100).toFixed(2)}%/week` : '—';
        if (mGap) mGap.innerText = has(f.earning_gap_irregularity)
            ? `${f.earning_gap_irregularity.toFixed(2)} days (σ)` : '—';
        if (mRating) mRating.innerText = has(f.avg_platform_rating)
            ? `${f.avg_platform_rating.toFixed(2)} ★ (${f.rating_trend >= 0 ? '+' : ''}${has(f.rating_trend) ? f.rating_trend.toFixed(2) : '?'} /mo)` : 'not linked';
        if (mCompleteness) mCompleteness.innerText = has(f.data_completeness)
            ? `${Math.round(f.data_completeness * 100)}%` : '—';
        if (mCohort && data.cohort) {
            mCohort.innerText = [data.cohort.gig_type, data.cohort.city, data.cohort.vehicle_class]
                .filter(has).join(' · ') || '—';
        }

        // SHAP reason cards
        if (reasonsList) {
            reasonsList.innerHTML = '';
            (data.reasons || []).forEach(r => {
                const li = document.createElement('li');
                li.className = r.type === 'negative' ? 'neg' : 'pos';
                const sign = r.impact_points >= 0 ? '+' : '';
                li.innerHTML = `<span class="impact">${sign}${r.impact_points}</span>` +
                    `<span class="label">${r.feature}</span>`;
                reasonsList.appendChild(li);
            });
        }

        // Counterfactual suggestions
        if (cfList) {
            cfList.innerHTML = '';
            (data.counterfactuals || []).forEach(cf => {
                const li = document.createElement('li');
                li.className = 'cf';
                li.innerHTML = `<span class="impact">+${Math.round(cf.delta_points)}</span>` +
                    `<span class="label">${cf.action}</span>`;
                cfList.appendChild(li);
            });
            if (!(data.counterfactuals || []).length) {
                const li = document.createElement('li');
                li.className = 'cf';
                li.innerHTML = '<span class="label">No single change would add 3+ points — profile is already strong.</span>';
                cfList.appendChild(li);
            }
        }

        // Radar chart (axes defined above)
        radarChart.data.datasets[1].data = data.radar_values;
        radarChart.update();

    } catch (error) {
        console.error("Error fetching user data:", error);
        alert("Failed to fetch data from backend server.");
    }
}
