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
                pointLabels: { color: '#94a3b8' },
                ticks: { color: '#94a3b8', backdropColor: 'transparent' },
                suggestedMin: 0,
                suggestedMax: 100
            }
        }
    }
});

// 3. Fetch User Data from Backend by User ID
async function fetchUserData() {
    const userId = document.getElementById('userIdSearch').value.trim();
    if (!userId) {
        alert("Please enter a valid User ID (e.g., GIG0001)");
        return;
    }

    try {
        const response = await fetch(`/api/user/${userId}`);
        if (!response.ok) {
            alert(`User ID ${userId} not found! Try GIG0001 to GIG5000.`);
            return;
        }

        const data = await response.json();

        // Update trust score
        if (scoreDisplay) {
            scoreDisplay.innerText = data.predicted_trust_score;
        }

        // Update UI sliders and labels if available
        if (volatilityInput && volatilityVal) {
            volatilityInput.value = data.features.income_volatility;
            volatilityVal.innerText = `${Math.round(data.features.income_volatility * 100)}%`;
        }
        if (upiInput && upiVal) {
            upiInput.value = data.features.upi_monthly_txns;
            upiVal.innerText = data.features.upi_monthly_txns;
        }
        if (utilityInput && utilityVal) {
            utilityInput.value = data.features.utility_on_time_ratio;
            utilityVal.innerText = `${Math.round(data.features.utility_on_time_ratio * 100)}%`;
        }

        // Update Radar Chart dataset
        radarChart.data.datasets[1].data = data.radar_values;
        radarChart.update();

    } catch (error) {
        console.error("Error fetching user data:", error);
        alert("Failed to fetch data from backend server.");
    }
}