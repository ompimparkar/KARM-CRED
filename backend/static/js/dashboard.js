// 1. DOM Elements
const volatilityInput = document.getElementById('volatility');
const upiInput = document.getElementById('upi');
const utilityInput = document.getElementById('utility');

const volatilityVal = document.getElementById('volatility-val');
const upiVal = document.getElementById('upi-val');
const utilityVal = document.getElementById('utility-val');
const scoreDisplay = document.getElementById('score-display');

// 2. Initialize Chart.js Radar Chart
const ctx = document.getElementById('creditRadarChart').getContext('2d');
const radarChart = new Chart(ctx, {
    type: 'radar',
    data: {
        labels: ['Income Stability', 'UPI Activity', 'Utility Reliability', 'Traditional Credit', 'Alternative Trust'],
        datasets: [
            {
                label: 'Traditional CIBIL',
                data: [20, 10, 30, 80, 20],
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
                suggestedMin: 0,
                suggestedMax: 100,
                ticks: { display: false }
            }
        }
    }
});

// 3. Function to Fetch Scores from Om's API
async function fetchScore() {
    const payload = {
        volatility: parseFloat(volatilityInput.value),
        upi: parseInt(upiInput.value),
        utility: parseFloat(utilityInput.value)
    };

    try {
        const response = await fetch('/api/score', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (!response.ok) throw new Error('API request failed');

        const data = await response.json();

        // Update score text on screen
        scoreDisplay.textContent = data.karm_score;

        // Update Chart.js dataset dynamically
        // Assuming data.radar_metrics returns array like [stability, upi, utility, cibil, karm]
        if (data.radar_metrics) {
            radarChart.data.datasets[1].data = data.radar_metrics;
            radarChart.update();
        }
    } catch (error) {
        console.error('Error fetching score:', error);
        // Fallback for testing UI before backend endpoint is ready
        scoreDisplay.textContent = Math.round(500 + (payload.utility * 3) - (payload.volatility * 2));
    }
}

// 4. Listeners for Real-Time Slider Movement
[volatilityInput, upiInput, utilityInput].forEach(slider => {
    slider.addEventListener('input', () => {
        // Update label text numbers
        volatilityVal.textContent = volatilityInput.value;
        upiVal.textContent = upiInput.value;
        utilityVal.textContent = utilityInput.value;

        // Trigger API call
        fetchScore();
    });
});

// Initial load call
fetchScore();