// Mock DB for static testing
console.log("KARM CRED Engine Loaded Successfully!");
const mockUserDatabase = {
    "GIG0001": { name: "Rohan (Delivery Partner)", volatility: 0.20, upi: 120, utility: 0.90 },
    "GIG0002": { name: "Priya (Freelance Designer)", volatility: 0.55, upi: 210, utility: 0.95 },
    "GIG0003": { name: "Amit (Rideshare Driver)", volatility: 0.80, upi: 35, utility: 0.50 }
};

let activeUserId = "GIG0001";
let radarChart;

// Generate dynamic profile for GIG0001 to GIG5000
function getOrGenerateUser(query) {
    if (mockUserDatabase[query]) {
        return mockUserDatabase[query];
    }

    const match = query.match(/^GIG(\d{1,4})$/);
    if (!match) return null;

    const idNum = parseInt(match[1], 10);
    if (idNum < 1 || idNum > 5000) return null;

    const pseudoRandom = (seed) => {
        const x = Math.sin(idNum + seed) * 10000;
        return x - Math.floor(x);
    };

    const roles = ["Delivery Partner", "Rideshare Driver", "Freelance Designer", "E-commerce Vendor", "Gig Worker"];
    const names = ["Aarav", "Ananya", "Vikram", "Sneha", "Karan", "Riya", "Rahul", "Pooja", "Arjun", "Neha"];

    return {
        name: `${names[idNum % names.length]} (${roles[idNum % roles.length]})`,
        volatility: parseFloat((pseudoRandom(1) * 0.8 + 0.1).toFixed(2)),
        upi: Math.floor(pseudoRandom(2) * 240 + 10),
        utility: parseFloat((pseudoRandom(3) * 0.6 + 0.4).toFixed(2))
    };
}

// Search handler
function searchUser() {
    const query = document.getElementById('userIdSearch').value.trim().toUpperCase();
    const user = getOrGenerateUser(query);

    if (user) {
        activeUserId = query;

        document.getElementById('activeUserId').textContent = `${query} (${user.name})`;
        document.getElementById('volatility').value = user.volatility;
        document.getElementById('upi').value = user.upi;
        document.getElementById('utility').value = user.utility;

        updateValueLabels();
        recalculateScore();
    } else {
        alert('Invalid User ID! Please enter a valid ID from GIG0001 to GIG5000.');
    }
}