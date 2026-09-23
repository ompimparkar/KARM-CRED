/* =========================================================================
 * KARM CRED dashboard - single-file client app.
 * Hash router + 6 views (client-side switching, no full reloads):
 *   #/score (default)  #/leaderboard  #/simulator
 *   #/compare          #/fairness     #/consent
 * Existing single-user lookup flow (fetchUserData / GET /api/user/<id>)
 * is preserved as-is and reused by the leaderboard cards.
 * ========================================================================= */

// ---------------------------------------------------------------- utils --
const has = (v) => v !== null && v !== undefined &&
    !(typeof v === 'number' && Number.isNaN(v));

const esc = (s) => String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const bandOf = (score) => (score >= 700 ? 'good' : score >= 500 ? 'mid' : 'low');
const sign = (v) => (v >= 0 ? '+' : '') + v;
const sgn1 = (v) => (v >= 0 ? '+' : '') + (+v).toFixed(1);

function setScore(el, score) {
    if (!el) return;
    el.textContent = has(score) ? score : '—';
    el.className = el.className.replace(/\b(good|mid|low)\b/g, '').trim() +
        (has(score) ? ' ' + bandOf(score) : '');
}

function setState(el, cls, html) {
    if (!el) return;
    if (!cls) { el.hidden = true; el.textContent = ''; return; }
    el.hidden = false;
    el.className = 'page-state ' + cls;
    el.innerHTML = html || '';
}

/** Format one model feature for humans (server supplies the format). */
function fmtFeature(key, v, fmt) {
    if (!has(v)) return '—';
    switch (fmt) {
        case 'money': return '₹' + Math.round(v).toLocaleString('en-IN');
        case 'signed_pct': return sign((v * 100).toFixed(1)) + '%';
        case 'ratio': return Math.round(v * 100) + '%';
        case 'days': return (+v).toFixed(2) + ' days (σ)';
        case 'int': return Math.round(v).toString();
        case 'months': return Math.round(v) + ' mo';
        case 'rating': return (+v).toFixed(1) + ' ★';
        case 'signed_num': return sign((+v).toFixed(2));
        default: return String(v);
    }
}

const pctOf = (v) => (has(v) ? Math.round(v * 100) + '%' : '—');
const money = (v) => (has(v) ? '₹' + Math.round(v).toLocaleString('en-IN') : '—');
const num1 = (v) => (has(v) ? (+v).toFixed(1) : '—');
const sgnPct1 = (v) => (has(v) ? sign((v * 100).toFixed(1)) + '%' : '—');
const sgnPct2 = (v) => (has(v) ? sign((v * 100).toFixed(2)) + '%' : '—');

const cohortStr = (c) => (c ? [c.gig_type, c.city, c.vehicle_class]
    .filter(has).join(' · ') : '') || '—';

const chipHtml = (label, val) =>
    `<span class="chip"><i>${label}</i>${val}</span>`;

/** Shared config from /api/feature_defaults (one request, cached client-side). */
let defaultsPromise = null;
function ensureDefaults() {
    if (!defaultsPromise) {
        defaultsPromise = fetch('/api/feature_defaults').then((r) => {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        });
    }
    return defaultsPromise;
}

// -------------------------------------------------------- shared render --
function renderReasons(el, reasons) {
    if (!el) return;
    el.innerHTML = '';
    (reasons || []).forEach((r) => {
        const li = document.createElement('li');
        li.className = r.type === 'negative' ? 'neg' : 'pos';
        const s = r.impact_points >= 0 ? '+' : '';
        li.innerHTML = `<span class="impact">${s}${r.impact_points}</span>` +
            `<span class="label">${r.feature}</span>`;
        el.appendChild(li);
    });
}

function renderCounterfactuals(el, cfs) {
    if (!el) return;
    el.innerHTML = '';
    (cfs || []).forEach((cf) => {
        const li = document.createElement('li');
        li.className = 'cf';
        li.innerHTML = `<span class="impact">+${Math.round(cf.delta_points)}</span>` +
            `<span class="label">${cf.action}</span>`;
        el.appendChild(li);
    });
    if (!(cfs || []).length) {
        const li = document.createElement('li');
        li.className = 'cf';
        li.innerHTML = '<span class="label">No single change would add 3+ points — profile is already strong.</span>';
        el.appendChild(li);
    }
}

// ============================== score page (existing flow) ===============
const RADAR_LABELS = ['Income Floor', 'UPI Activity', 'Utility Reliability',
    'Platform Rating', 'Savings Discipline'];

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

let radarChart = null;
let lastScoredUserId = null;  // used by the Data & Consent live preview

function radarOptions() {
    return {
        responsive: true,
        scales: {
            r: {
                angleLines: { color: '#334155' },
                grid: { color: '#334155' },
                pointLabels: { color: '#94a3b8' },
                ticks: { color: '#94a3b8', backdropColor: 'transparent' },
                suggestedMin: 0,
                suggestedMax: 100,
            },
        },
        plugins: { legend: { labels: { color: '#e2e8f0' } } },
    };
}

function ensureRadar() {
    if (radarChart) { radarChart.resize(); return radarChart; }
    const ctx = document.getElementById('creditRadarChart').getContext('2d');
    radarChart = new Chart(ctx, {
        type: 'radar',
        data: {
            labels: RADAR_LABELS,
            datasets: [
                {
                    label: 'Traditional CIBIL',
                    data: [10, 40, 50, 15, 20],
                    backgroundColor: 'rgba(239, 68, 68, 0.2)',
                    borderColor: '#ef4444',
                    pointBackgroundColor: '#ef4444',
                },
                {
                    label: 'KARM CRED',
                    data: [50, 60, 85, 40, 75],
                    backgroundColor: 'rgba(56, 189, 248, 0.2)',
                    borderColor: '#38bdf8',
                    pointBackgroundColor: '#38bdf8',
                },
            ],
        },
        options: radarOptions(),
    });
    return radarChart;
}

function renderScorePage(data) {
    const f = data.features || {};
    const state = document.getElementById('scoreState');

    // Trust score + one-line natural-language summary
    setScore(scoreDisplay, data.predicted_trust_score);
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
    if (mCohort && data.cohort) mCohort.innerText = cohortStr(data.cohort);

    renderReasons(reasonsList, data.reasons);
    renderCounterfactuals(cfList, data.counterfactuals);

    // Radar chart
    ensureRadar().data.datasets[1].data = data.radar_values;
    radarChart.update();

    setState(state, null);
}

// Fetch User Data from Backend by User ID (UNCHANGED API contract)
async function fetchUserData() {
    const input = document.getElementById('userIdSearch');
    const userId = (input ? input.value : '').trim();
    const state = document.getElementById('scoreState');
    if (!userId) {
        setState(state, 'error',
            'Please enter a valid User ID (e.g. GIG_0001, or try TWIN_HEALTHY / TWIN_RISKY).');
        return;
    }

    setState(state, 'loading', `Scoring <b>${esc(userId)}</b>…`);

    try {
        const response = await fetch(`/api/user/${encodeURIComponent(userId)}`);
        if (!response.ok) {
            const err = await response.json().catch(() => ({}));
            const sample = (err.sample_ids_in_csv || []).length
                ? ` Sample ids: ${err.sample_ids_in_csv.map(esc).join(', ')}.` : '';
            setState(state, 'error', esc(err.error ||
                `User ID ${userId} not found! Try GIG_0001 to GIG_5000.`) + sample);
            return;
        }

        const data = await response.json();
        lastScoredUserId = data.user_id;
        consentBaseline = null;  // a different worker may become the preview target
        renderScorePage(data);
    } catch (error) {
        console.error('Error fetching user data:', error);
        setState(state, 'error',
            'Failed to reach the backend — is it running on port 5000?');
    }
}

/** Open a worker in My TrustScore (used by leaderboard cards). */
function openScore(userId) {
    const input = document.getElementById('userIdSearch');
    if (input) input.value = userId;
    if (location.hash !== '#/score') { location.hash = '#/score'; }  // routes
    fetchUserData();
}

// ================================ router =================================
const ROUTES = ['score', 'leaderboard', 'simulator', 'compare', 'fairness', 'consent'];
let currentRoute = null;

function routeFromHash() {
    const h = (location.hash || '').replace(/^#\/?/, '').split(/[?&]/)[0];
    return ROUTES.indexOf(h) >= 0 ? h : 'score';
}

const ON_ENTER = {
    score: () => ensureRadar(),
    leaderboard: () => ensureLeaderboard(),
    simulator: () => ensureSimulator(),
    compare: () => {
        const a = document.getElementById('cmpA');
        if (a && !a.value) a.focus();
    },
    fairness: () => ensureFairness(),
    consent: () => ensureConsentPreview(),
};

function navigate() {
    const route = routeFromHash();
    if (route === currentRoute) return;
    currentRoute = route;

    ROUTES.forEach((r) => {
        const page = document.getElementById('page-' + r);
        if (page) page.hidden = (r !== route);
    });
    document.querySelectorAll('.nav-item').forEach((a) => {
        a.classList.toggle('active', a.dataset.route === route);
    });
    const page = document.getElementById('page-' + route);
    const title = document.getElementById('pageTitle');
    if (page && title) title.textContent = page.dataset.title || '';
    closeSidebar();
    if (ON_ENTER[route]) ON_ENTER[route]();
    window.scrollTo({ top: 0 });
}
window.addEventListener('hashchange', navigate);

// ============================== leaderboard ==============================
let lbLoaded = false;

async function ensureLeaderboard() {
    if (lbLoaded) return;
    const state = document.getElementById('lbState');
    setState(state, 'loading',
        'Scoring the population (first load builds the score cache)…');
    try {
        const res = await fetch('/api/leaderboard');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const lb = await res.json();
        renderLeaderboard(lb);
        lbLoaded = true;
        setState(state, null);
        const meta = document.getElementById('lbMeta');
        meta.textContent = `Population n=${lb.population_count.toLocaleString('en-IN')} · ` +
            `median score ${lb.score_median} · click any card to open it in My TrustScore.`;
    } catch (e) {
        setState(state, 'error',
            `Leaderboard failed to load (${esc(e.message)}). Is the backend running? ` +
            '<button class="btn ghost small" data-retry="lb">Retry</button>');
    }
}

function renderLeaderboard(lb) {
    ['top', 'middle', 'bottom'].forEach((tab) => {
        const el = document.getElementById('lb-' + tab);
        if (el) el.innerHTML = lb[tab].map(lbCardHtml).join('');
    });
    // only the active tab visible (default: top)
    const active = document.querySelector('#lbTabs .tab.active');
    const activeTab = active ? active.dataset.tab : 'top';
    ['top', 'middle', 'bottom'].forEach((t) => {
        document.getElementById('lb-' + t).hidden = (t !== activeTab);
    });
}

function lbCardHtml(e) {
    const s = e.predicted_trust_score;
    const reasons = (e.top_reasons || []).map((r) =>
        `<li class="${r.type}"><span class="impact">${r.impact_points >= 0 ? '+' : ''}${r.impact_points}</span>` +
        `<span class="label">${r.feature}</span></li>`).join('');
    const h = e.highlights || {};
    const chips = [
        chipHtml('Floor', pctOf(h.income_floor_ratio) + ' of typical'),
        chipHtml('Trend', sgnPct2(h.income_trend_slope) + '/wk'),
        chipHtml('Gaps', fmtFeature('earning_gap_irregularity', h.earning_gap_irregularity, 'days')),
        has(h.utility_on_time_ratio)
            ? chipHtml('Utility', pctOf(h.utility_on_time_ratio) + ' on-time')
            : chipHtml('Swings', sgnPct1(h.cohort_adjusted_volatility) + ' vs peers'),
        has(h.avg_platform_rating)
            ? chipHtml('Rating', fmtFeature('avg_platform_rating', h.avg_platform_rating, 'rating')) : '',
    ].join('');
    return `<div class="lb-card" data-user="${e.user_id}" role="button" tabindex="0"
                 title="Open ${e.user_id} in My TrustScore">
        <div class="lb-card-top">
            <span class="lb-user">${e.user_id}</span>
            <span class="score-band ${bandOf(s)}">${s}</span>
        </div>
        <ul class="lb-reasons">${reasons}</ul>
        <div class="lb-chips">${chips}</div>
    </div>`;
}

function retryLeaderboard() { lbLoaded = false; ensureLeaderboard(); }

// =============================== simulator ===============================
let simDefaults = null;
let simValues = {};
let simTimer = null;
let simSeq = 0;
let simLoaded = false;

async function ensureSimulator() {
    if (simLoaded) return;
    const state = document.getElementById('simState');
    setState(state, 'loading', 'Loading slider ranges from the training data…');
    try {
        simDefaults = await ensureDefaults();
        buildSimSliders();
        resetToTypical();   // dataset medians -> triggers first simulate()
        simLoaded = true;
        setState(state, null);
        document.getElementById('simBody').hidden = false;
    } catch (e) {
        setState(state, 'error',
            `Couldn't load slider config (${esc(e.message)}). Is the backend running? ` +
            '<button class="btn ghost small" data-retry="sim">Retry</button>');
    }
}

function retrySimulator() { simLoaded = false; defaultsPromise = null; ensureSimulator(); }

function buildSimSliders() {
    const box = document.getElementById('simSliders');
    box.innerHTML = '';
    const { labels, ranges, steps, formats } = simDefaults;
    Object.keys(labels).forEach((f) => {
        const min = ranges[f][0];
        const max = ranges[f][1];
        const step = steps[f];
        const wrap = document.createElement('div');
        wrap.className = 'slider-group sim-slider';
        wrap.innerHTML =
            `<label for="sim-${f}">${labels[f]}: <span id="simv-${f}">—</span></label>` +
            `<input type="range" id="sim-${f}" data-f="${f}" min="${min}" max="${max}" step="${step}" value="${min}">`;
        box.appendChild(wrap);
        const input = wrap.querySelector('input');
        input.addEventListener('input', () => {
            simValues[f] = parseFloat(input.value);
            document.getElementById('simv-' + f).textContent =
                fmtFeature(f, simValues[f], formats[f]);
            scheduleSim();
        });
    });
}

function resetToTypical() {
    const { medians, ranges, steps, formats } = simDefaults;
    Object.keys(medians).forEach((f) => {
        const min = ranges[f][0];
        const step = steps[f];
        const snapped = has(step) && step > 0
            ? min + Math.round((medians[f] - min) / step) * step : medians[f];
        simValues[f] = snapped;
        const input = document.getElementById('sim-' + f);
        if (input) input.value = snapped;
        const val = document.getElementById('simv-' + f);
        if (val) val.textContent = fmtFeature(f, snapped, formats[f]);
    });
    scheduleSim();
}

function scheduleSim() {
    clearTimeout(simTimer);
    simTimer = setTimeout(runSimulate, 200);   // debounce drags
}

async function runSimulate() {
    const seq = ++simSeq;
    const scoreEl = document.getElementById('sim-score');
    if (scoreEl) scoreEl.classList.add('pending');
    const state = document.getElementById('simState');
    try {
        const res = await fetch('/api/simulate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(simValues),
        });
        if (seq !== simSeq) return;   // stale response, a newer call is in flight
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            setState(state, 'error', esc(data.error || 'Simulation failed.') +
                (data.missing_fields ? ` (${data.missing_fields.join(', ')})` : ''));
            return;
        }
        if (simLoaded) setState(state, null);
        renderSim(data);
    } catch (e) {
        if (seq !== simSeq) return;
        setState(state, 'error', 'Simulator call failed: ' + esc(e.message));
    } finally {
        if (scoreEl) scoreEl.classList.remove('pending');
    }
}

function renderSim(data) {
    setScore(document.getElementById('sim-score'), data.predicted_trust_score);
    document.getElementById('sim-summary').textContent = data.summary || '';
    renderReasons(document.getElementById('sim-reasons'), data.reasons);
    renderCounterfactuals(document.getElementById('sim-cf'), data.counterfactuals);

    // "What would help most" hint: biggest lever = top counterfactual,
    // otherwise the strongest positive SHAP contribution.
    const hint = document.getElementById('simHint');
    const cf = (data.counterfactuals || [])[0];
    const pos = (data.reasons || []).find((r) => r.type === 'positive');
    if (cf) {
        document.getElementById('simHintText').textContent =
            `${cf.action} (about +${Math.round(cf.delta_points)} pts)`;
        hint.hidden = false;
    } else if (pos) {
        document.getElementById('simHintText').textContent =
            `${pos.feature} is already your biggest strength (${sign(pos.impact_points)} pts) — no single change adds 3+ pts right now.`;
        hint.hidden = false;
    } else {
        hint.hidden = true;
    }
}

// ================================ compare ================================
let compareRadar = null;

async function runCompare() {
    const a = document.getElementById('cmpA').value.trim();
    const b = document.getElementById('cmpB').value.trim();
    const state = document.getElementById('cmpState');
    const body = document.getElementById('cmpBody');
    if (!a || !b) {
        setState(state, 'error', 'Both worker IDs are required.');
        return;
    }
    body.hidden = true;
    setState(state, 'loading', `Comparing <b>${esc(a)}</b> vs <b>${esc(b)}</b>…`);
    try {
        const res = await fetch(`/api/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`);
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            setState(state, 'error', esc(data.error || 'Compare failed.') +
                (data.side ? ` (${data.side})` : ''));
            return;
        }
        setState(state, null);
        body.hidden = false;
        renderCompare(data);
    } catch (e) {
        setState(state, 'error', 'Backend unreachable: ' + esc(e.message));
    }
}

function renderCompare(d) {
    const A = d.user_a;
    const B = d.user_b;
    const gap = d.score_gap;

    document.getElementById('cmpSummary').innerHTML =
        `<span class="gap-badge ${gap >= 0 ? 'a' : 'b'}">${Math.abs(gap)}-point gap</span> ` +
        compareSentence(A, B, d.diff_reasons, gap);

    document.getElementById('cmpCardA').innerHTML = cmpCardHtml('Worker A', A, 'a');
    document.getElementById('cmpCardB').innerHTML = cmpCardHtml('Worker B', B, 'b');

    document.getElementById('cmpDiff').innerHTML = (d.diff_reasons || []).map((x) => `
        <li class="${x.delta >= 0 ? 'pos' : 'neg'}">
            <span class="impact">${sign(x.delta)}</span>
            <span class="label">${x.feature}
                <small class="sub">A ${sign(x.impact_a)} · B ${sign(x.impact_b)} pts</small>
            </span>
        </li>`).join('');

    ensureCompareRadar(A.radar_values, B.radar_values, A.user_id, B.user_id);
    renderCmpTable(A, B, d.diff_reasons || []);
}

function cmpCardHtml(side, u, which) {
    return `<div class="cmp-card-head">
            <span class="cmp-side side-${which}">${side} · <b>${u.user_id}</b></span>
            <span class="score-band ${bandOf(u.predicted_trust_score)}">${u.predicted_trust_score}</span>
        </div>
        <p class="cmp-cohort">${cohortStr(u.cohort)}</p>
        <p class="summary-line">${u.summary || ''}</p>`;
}

/** One-line auto-generated comparison sentence (templated, never hardcoded). */
function compareSentence(A, B, diffs, gap) {
    const fa = A.features || {};
    const fb = B.features || {};
    const labels = {};
    (A.reasons || []).forEach((r) => { labels[r.key] = r.feature; });

    const incomeSame = Math.abs((fa.monthly_avg_income || 0) -
        (fb.monthly_avg_income || 0)) < 1000;
    const head = incomeSame
        ? `Both workers average ${money(fa.monthly_avg_income)}/month`
        : `Worker A averages ${money(fa.monthly_avg_income)}/month vs B's ${money(fb.monthly_avg_income)}`;

    const volDelta = Math.abs((fa.cohort_adjusted_volatility || 0) -
        (fb.cohort_adjusted_volatility || 0));
    const volSame = volDelta < 0.02;
    const volPart = volSame
        ? ` with near-identical swings vs. their peers (${sgnPct1(fa.cohort_adjusted_volatility)})`
        : ` with swings of ${sgnPct1(fa.cohort_adjusted_volatility)} vs ${sgnPct1(fb.cohort_adjusted_volatility)} against their peers`;

    const clauses = (diffs || []).slice(0, 2)
        .map((x) => clauseFor(x.key, fa, fb, labels)).filter(Boolean);

    return `${head}${volPart}, but ${clauses.join(' while ')} — ` +
        `that pattern explains most of the ${Math.abs(gap)}-point gap ` +
        `(${A.predicted_trust_score} vs ${B.predicted_trust_score}).`;
}

function clauseFor(key, fa, fb, labels) {
    const a = fa[key];
    const b = fb[key];
    switch (key) {
        case 'income_floor_ratio':
            return `A's income floor holds ${pctOf(a)} of a typical week while B's falls to ${pctOf(b)}`;
        case 'income_trend_slope':
            return `A's earnings trend is ${sgnPct2(a)} per week vs B's ${sgnPct2(b)}`;
        case 'earning_gap_irregularity':
            return `A's payday gaps swing ${num1(a)} days vs B's ${num1(b)} days`;
        case 'cohort_adjusted_volatility':
            return `A swings ${sgnPct1(a)} vs similar gig workers while B swings ${sgnPct1(b)}`;
        case 'monthly_avg_income':
            return `A earns ${money(a)}/month vs B's ${money(b)}`;
        case 'avg_platform_rating':
            return `A's platform rating is ${fmtFeature('avg_platform_rating', a, 'rating')} vs B's ${fmtFeature('avg_platform_rating', b, 'rating')}`;
        case 'utility_on_time_ratio':
            return `A pays utility bills on time ${pctOf(a)} of the time vs B's ${pctOf(b)}`;
        case 'upi_monthly_txns':
            return `A logs ${fmtFeature('upi_monthly_txns', a, 'int')} monthly UPI txns vs B's ${fmtFeature('upi_monthly_txns', b, 'int')}`;
        case 'avg_savings_ratio':
            return `A saves ${pctOf(a)} of earnings vs B's ${pctOf(b)}`;
        case 'platform_tenure_months':
            return `A has ${fmtFeature('platform_tenure_months', a, 'months')} on-platform vs B's ${fmtFeature('platform_tenure_months', b, 'months')}`;
        case 'rating_trend':
            return `A's rating trend is ${has(a) ? sign((+a).toFixed(2)) : '—'}/mo vs B's ${has(b) ? sign((+b).toFixed(2)) : '—'}/mo`;
        case 'data_completeness':
            return `A's profile completeness is ${pctOf(a)} vs B's ${pctOf(b)}`;
        default:
            return `${labels[key] || key}: A ${a} vs B ${b}`;
    }
}

async function renderCmpTable(A, B, diffs) {
    const highlight = new Set(diffs.map((x) => x.key));
    const fd = await ensureDefaults().catch(() => null);
    const labels = (fd && fd.labels) || {};
    const formats = (fd && fd.formats) || {};
    const labelOf = (key) => labels[key] ||
        ((A.reasons || []).find((r) => r.key === key) || {}).feature || key;

    const bMap = {};
    (B.reasons || []).forEach((r) => { bMap[r.key] = r.impact_points; });

    const tbody = document.querySelector('#cmpTable tbody');
    tbody.innerHTML = (A.reasons || []).map((r) => {
        const key = r.key;
        const bImp = has(bMap[key]) ? bMap[key] : 0;
        const delta = +((r.impact_points - bImp)).toFixed(1);
        return `<tr class="${highlight.has(key) ? 'diff-row' : ''}">
            <td>${labelOf(key)}</td>
            <td>${fmtFeature(key, (A.features || {})[key], formats[key])}</td>
            <td>${fmtFeature(key, (B.features || {})[key], formats[key])}</td>
            <td class="${r.impact_points >= 0 ? 'pos' : 'neg'}">${sign(r.impact_points)}</td>
            <td class="${bImp >= 0 ? 'pos' : 'neg'}">${sign(bImp)}</td>
            <td class="${delta >= 0 ? 'pos' : 'neg'}"><b>${sign(delta)}</b></td>
        </tr>`;
    }).join('');
}

function ensureCompareRadar(aVals, bVals, idA, idB) {
    const ctx = document.getElementById('compareRadar');
    if (compareRadar) {
        compareRadar.data.datasets[0].data = aVals;
        compareRadar.data.datasets[0].label = idA;
        compareRadar.data.datasets[1].data = bVals;
        compareRadar.data.datasets[1].label = idB;
        compareRadar.update();
        return;
    }
    compareRadar = new Chart(ctx, {
        type: 'radar',
        data: {
            labels: RADAR_LABELS,
            datasets: [
                {
                    label: idA, data: aVals,
                    borderColor: '#38bdf8', backgroundColor: 'rgba(56, 189, 248, 0.18)',
                    pointBackgroundColor: '#38bdf8',
                },
                {
                    label: idB, data: bVals,
                    borderColor: '#f59e0b', backgroundColor: 'rgba(245, 158, 11, 0.15)',
                    pointBackgroundColor: '#f59e0b',
                },
            ],
        },
        options: radarOptions(),
    });
}

// =============================== fairness ================================
let fairLoaded = false;
let decileChart = null;
let histChart = null;
let importanceChart = null;

async function ensureFairness() {
    if (fairLoaded) return;
    const state = document.getElementById('fairState');
    setState(state, 'loading',
        'Crunching population stats (first load runs one batch SHAP pass)…');
    try {
        const res = await fetch('/api/fairness');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const f = await res.json();
        fairLoaded = true;
        setState(state, null);
        document.getElementById('fairBody').hidden = false;
        renderFairness(f);
    } catch (e) {
        setState(state, 'error',
            `Fairness stats failed to load (${esc(e.message)}). Is the backend running? ` +
            '<button class="btn ghost small" data-retry="fair">Retry</button>');
    }
}

function retryFairness() { fairLoaded = false; ensureFairness(); }

function renderFairness(f) {
    const ds = f.score_by_volatility_decile || [];
    const tol = f.max_acceptable_gap_points;
    const txt = (id, v) => {
        const el = document.getElementById(id);
        if (el) el.textContent = v;
    };

    // ---- stat row ----
    txt('stHi', f.healthy_high_volatility_avg_score);
    txt('stHiN', `n = ${(f.high_volatility_healthy_count || 0).toLocaleString('en-IN')} workers`);
    txt('stLo', f.low_volatility_avg_score);
    txt('stLoN', `n = ${(f.low_volatility_count || 0).toLocaleString('en-IN')} workers`);
    const gap = f.gap_vs_low_volatility;
    const gapOk = gap >= -tol;
    const gapEl = document.getElementById('stGap');
    gapEl.textContent = `${gap >= 0 ? '+' : ''}${gap} pts`;
    gapEl.className = gapOk ? 'good' : 'bad';
    txt('stGapNote', gapOk
        ? `within ±${tol}-pt tolerance → NOT underscored`
        : `outside ±${tol}-pt tolerance → UNDERSHOT`);
    const decGap = f.healthy_decile_gap;
    txt('stDec', has(decGap) ? `${decGap >= 0 ? '+' : ''}${decGap} pts` : '—');
    txt('exDecGap', has(decGap) ? `${decGap >= 0 ? '+' : ''}${decGap} points` : 'does not fall');
    txt('exTol', tol);

    // ---- decile chart: overall bars + healthy line ----
    decileChart = new Chart(document.getElementById('decileChart'), {
        type: 'bar',
        data: {
            labels: ds.map((x) => 'D' + x.decile),
            datasets: [
                {
                    label: 'All workers',
                    data: ds.map((x) => x.avg_score),
                    backgroundColor: 'rgba(239, 68, 68, 0.55)',
                    borderColor: '#ef4444', borderWidth: 1, order: 2,
                },
                {
                    label: 'Otherwise-healthy only',
                    data: ds.map((x) => x.avg_score_healthy),
                    type: 'line', borderColor: '#4ade80', backgroundColor: '#4ade80',
                    borderWidth: 2.5, tension: 0.3, pointRadius: 4, order: 1,
                    spanGaps: true,
                },
            ],
        },
        options: {
            responsive: true, aspectRatio: 1.5,
            scales: {
                y: {
                    min: 400, max: 750,
                    grid: { color: '#334155' }, ticks: { color: '#94a3b8' },
                    title: { display: true, text: 'avg score (400–750 zoom)', color: '#64748b' },
                },
                x: { grid: { color: '#1e293b' }, ticks: { color: '#94a3b8' } },
            },
            plugins: { legend: { labels: { color: '#e2e8f0' } } },
        },
    });

    // ---- score histogram ----
    const hist = f.score_histogram || [];
    histChart = new Chart(document.getElementById('histChart'), {
        type: 'bar',
        data: {
            labels: hist.map((b) => b.bucket),
            datasets: [{
                label: 'Workers',
                data: hist.map((b) => b.count),
                backgroundColor: 'rgba(56, 189, 248, 0.6)',
                borderColor: '#38bdf8', borderWidth: 1,
            }],
        },
        options: {
            responsive: true, aspectRatio: 1.6,
            scales: {
                y: { grid: { color: '#334155' }, ticks: { color: '#94a3b8' } },
                x: {
                    grid: { color: '#1e293b' },
                    ticks: { color: '#94a3b8', maxRotation: 60, minRotation: 60, font: { size: 10 } },
                },
            },
            plugins: { legend: { display: false } },
        },
    });

    const ps = f.population_stats || {};
    document.getElementById('popStats').innerHTML = [
        ['min', ps.min], ['median', ps.median], ['mean', ps.mean],
        ['max', ps.max], ['std', ps.std], ['n', ps.count],
    ].map(([k, v]) => chipHtml(k, has(v) ? v : '—')).join('');

    // ---- global SHAP importance ----
    const imp = f.global_feature_importance || [];
    importanceChart = new Chart(document.getElementById('importanceChart'), {
        type: 'bar',
        data: {
            labels: imp.map((x) => x.label),
            datasets: [{
                label: 'mean |SHAP| (score points)',
                data: imp.map((x) => x.mean_abs_shap),
                backgroundColor: 'rgba(56, 189, 248, 0.6)',
                borderColor: '#38bdf8', borderWidth: 1,
            }],
        },
        options: {
            indexAxis: 'y', responsive: true, aspectRatio: 0.85,
            scales: {
                x: { grid: { color: '#334155' }, ticks: { color: '#94a3b8' } },
                y: { grid: { color: '#1e293b' }, ticks: { color: '#e2e8f0', font: { size: 11 } } },
            },
            plugins: { legend: { display: false } },
        },
    });
    txt('impN', (ps.count || 0).toLocaleString('en-IN'));

    // ---- lender copy + verdict ----
    const lender = document.getElementById('lenderCopy');
    lender.innerHTML =
        `Across <b>${(ps.count || 0).toLocaleString('en-IN')}</b> scored workers, ` +
        `scores run <b>${ps.min}</b> / <b>${ps.median}</b> / <b>${ps.max}</b> ` +
        `(min / median / max, σ = ${ps.std}). The model reads <b>` +
        `${imp.slice(0, 3).map((x) => x.label).join('</b>, <b>')}</b> most — ` +
        `structure of income, not noise. For a lender: the flat 'volatility' number is ` +
        `deliberately NOT the decision feature; risk shows up through floor, trend and ` +
        `payday regularity, so a worker can swing hard weekly and still score well if the ` +
        `structure underneath is sound.`;
    const verdict = document.getElementById('fairVerdict');
    const decOk = !has(decGap) || decGap >= -tol;
    verdict.innerHTML = (gapOk && decOk)
        ? `<span class="badge ok">PASS</span> Healthy high-volatility workers are not systematically underscored ` +
          `(group gap ${gap >= 0 ? '+' : ''}${gap} · healthy D10−D1 ${has(decGap) ? (decGap >= 0 ? '+' : '') + decGap : 'n/a'} — ` +
          `both inside the ±${tol}-pt tolerance enforced when the model was trained).`
        : `<span class="badge bad">REVIEW</span> A gap exceeded the ±${tol}-pt tolerance — inspect before shipping.`;
}

// ============================== data & consent ===========================
/* Keep SOURCES/features in sync with backend/ml/data_utils.py SOURCE_OF_FEATURE. */
const SOURCES = [
    {
        id: 'upi',
        name: 'UPI & bank SMS metadata',
        what: 'Monthly UPI transaction count and regularity, derived from your SMS/UPI history.',
        why: 'A steady payments footprint stands in for the credit file gig workers rarely have.',
        features: ['upi_monthly_txns'],
    },
    {
        id: 'utility',
        name: 'Utility bill payments',
        what: 'On-time ratio for electricity / phone / postpaid bills in your name.',
        why: 'The closest existing analogue to paying a loan EMI on schedule.',
        features: ['utility_on_time_ratio'],
    },
    {
        id: 'platform',
        name: 'Gig platform rating & trend',
        what: 'Average customer rating and its month-over-month direction.',
        why: 'Reputation built across hundreds of gigs is real creditworthiness evidence.',
        features: ['avg_platform_rating', 'rating_trend'],
    },
];
/* Features never consent-gated: derived from your own earnings history. */
const ALWAYS_FEATURES = [
    'monthly_avg_income', 'cohort_adjusted_volatility', 'income_trend_slope',
    'income_floor_ratio', 'earning_gap_irregularity', 'platform_tenure_months',
    'avg_savings_ratio', 'data_completeness',
];

const CONSENT_KEY = 'karmcred.consent.v1';

function loadConsent() {
    try {
        const raw = localStorage.getItem(CONSENT_KEY);
        if (raw) {
            const p = JSON.parse(raw);
            return {
                upi: p.upi !== false,
                utility: p.utility !== false,
                platform: p.platform !== false,
            };
        }
    } catch (e) { /* private mode / disabled storage */ }
    return { upi: true, utility: true, platform: true };
}

function saveConsent(c) {
    try { localStorage.setItem(CONSENT_KEY, JSON.stringify(c)); } catch (e) { /* ignore */ }
}

let consent = loadConsent();
let consentBuilt = false;
let consentBaseline = null;   // score with ALL sources granted
let consentTimer = null;
let consentSeq = 0;

async function ensureConsentPreview() {
    if (!consentBuilt) {
        consentBuilt = true;
        renderSourceGrid();
    }
    await renderFeatureChips();
    await refreshConsentPreview(consentBaseline === null);
}

function renderSourceGrid() {
    const grid = document.getElementById('sourceGrid');
    grid.innerHTML = SOURCES.map((s) => `
        <div class="source-card ${consent[s.id] ? '' : 'off'}" data-source="${s.id}">
            <label class="switch">
                <input type="checkbox" data-source-toggle="${s.id}" ${consent[s.id] ? 'checked' : ''}>
                <span class="slider-knob"></span>
            </label>
            <div class="source-body">
                <b>${s.name}</b>
                <p class="source-what">${s.what}</p>
                <p class="source-why"><i>Why it's used:</i> ${s.why}</p>
                <div class="lb-chips">${s.features.map((f) => chipHtml('feature', f)).join('')}</div>
            </div>
        </div>`).join('');

    grid.querySelectorAll('input[data-source-toggle]').forEach((inp) => {
        inp.addEventListener('change', () => {
            consent[inp.dataset.sourceToggle] = inp.checked;
            saveConsent(consent);
            const card = inp.closest('.source-card');
            if (card) card.classList.toggle('off', !inp.checked);
            renderFeatureChips();
            scheduleConsentPreview();
        });
    });
}

async function renderFeatureChips() {
    const fd = await ensureDefaults().catch(() => null);
    const labels = (fd && fd.labels) || {};
    const srcOf = {};
    SOURCES.forEach((s) => s.features.forEach((f) => { srcOf[f] = s.id; }));
    const keys = fd ? Object.keys(fd.labels)
        : [...ALWAYS_FEATURES, ...Object.keys(srcOf)];
    const box = document.getElementById('featureChips');
    box.innerHTML = keys.map((k) => {
        const src = srcOf[k];
        const off = src ? !consent[src] : false;
        const label = labels[k] || k;
        const title = src
            ? `Source: ${src} — ${off ? 'withheld (nulled before the model runs)' : 'granted'}`
            : 'Derived from your earnings history — always used';
        return `<span class="feature-chip ${off ? 'off' : ''}" title="${title}">` +
            `${label}${off ? ' — withheld' : ''}</span>`;
    }).join('');
}

function scheduleConsentPreview() {
    clearTimeout(consentTimer);
    consentTimer = setTimeout(() => refreshConsentPreview(false), 250);
}

async function refreshConsentPreview(withBaseline) {
    const who = lastScoredUserId || 'GIG_0001';
    const whoEl = document.getElementById('cpWho');
    if (whoEl) whoEl.textContent = who;
    const state = document.getElementById('cpState');
    const granted = Object.keys(consent).filter((k) => consent[k]);
    const seq = ++consentSeq;

    try {
        if (withBaseline || consentBaseline === null) {
            const b = await fetch(`/api/user/${encodeURIComponent(who)}`);
            if (b.ok) {
                const bd = await b.json();
                consentBaseline = bd.predicted_trust_score;
            }
        }
        // empty ?consent= = nothing granted (strictest), matches the API gate
        const qs = '?consent=' + encodeURIComponent(granted.join(','));
        const res = await fetch(`/api/user/${encodeURIComponent(who)}${qs}`);
        if (seq !== consentSeq) return;
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            setState(state, 'error', esc(err.error || 'Preview failed.'));
            return;
        }
        const data = await res.json();
        setState(state, null);

        setScore(document.getElementById('cpScore'), data.predicted_trust_score);
        const deltaEl = document.getElementById('cpDelta');
        if (has(consentBaseline)) {
            const delta = data.predicted_trust_score - consentBaseline;
            deltaEl.textContent = delta === 0 ? '0 pts' : `${sign(delta)} pts`;
            deltaEl.className = delta === 0 ? 'good' : (delta < 0 ? 'bad' : 'good');
        } else {
            deltaEl.textContent = '—';
        }
        document.getElementById('cpComplete').textContent =
            has(data.features.data_completeness)
                ? `${Math.round(data.features.data_completeness * 100)}%` : '—';
        document.getElementById('cpSummary').textContent = data.summary || '';
    } catch (e) {
        if (seq !== consentSeq) return;
        setState(state, 'error', 'Preview failed: ' + esc(e.message));
    }
}

// ============================ mobile sidebar =============================
const sidebarEl = document.getElementById('sidebar');
const backdropEl = document.getElementById('sidebarBackdrop');

function closeSidebar() {
    if (sidebarEl) sidebarEl.classList.remove('open');
    if (backdropEl) backdropEl.classList.remove('show');
}

document.getElementById('hamburger').addEventListener('click', () => {
    const open = !sidebarEl.classList.contains('open');
    sidebarEl.classList.toggle('open', open);
    backdropEl.classList.toggle('show', open);
});
backdropEl.addEventListener('click', closeSidebar);

// ============================ wiring / init ==============================
document.getElementById('userIdSearch').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') fetchUserData();
});
document.getElementById('cmpA').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') runCompare();
});
document.getElementById('cmpB').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') runCompare();
});
document.getElementById('cmpRun').addEventListener('click', runCompare);
document.getElementById('cmpPreset').addEventListener('click', () => {
    document.getElementById('cmpA').value = 'TWIN_HEALTHY';
    document.getElementById('cmpB').value = 'TWIN_RISKY';
    runCompare();
});
document.getElementById('simReset').addEventListener('click', () => {
    if (simDefaults) resetToTypical();
});

// Leaderboard: tab switching + card clicks (delegated)
document.getElementById('lbTabs').addEventListener('click', (e) => {
    const btn = e.target.closest('.tab');
    if (!btn) return;
    const tab = btn.dataset.tab;
    document.querySelectorAll('#lbTabs .tab').forEach((t) =>
        t.classList.toggle('active', t === btn));
    ['top', 'middle', 'bottom'].forEach((t) => {
        const panel = document.getElementById('lb-' + t);
        if (panel) panel.hidden = (t !== tab);
    });
});
document.querySelectorAll('.card-grid').forEach((grid) => {
    const open = (target) => {
        const card = target.closest('.lb-card');
        if (card) openScore(card.dataset.user);
    };
    grid.addEventListener('click', (e) => open(e.target));
    grid.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(e.target); }
    });
});

// Generic retry buttons inside error states
document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-retry]');
    if (!btn) return;
    const which = btn.dataset.retry;
    if (which === 'lb') retryLeaderboard();
    if (which === 'sim') retrySimulator();
    if (which === 'fair') retryFairness();
});

// Boot: resolve the initial hash (deep links like #/fairness work).
navigate();
