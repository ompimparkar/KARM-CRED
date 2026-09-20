# KARM-CRED
Alternative credit scoring platform for the gig economy - CODEX 2026".
# KARM CRED 🚀
**Credit Score for the Invisible**

## 📌 Problem Statement
Millions of gig workers and freelancers are entirely invisible to traditional credit bureaus due to volatile income streams and a lack of formal credit history. When applying for loans or financial services, they are often flagged as high-risk, despite having healthy financial habits, utility payment histories, and consistent UPI transaction volumes. 

## 💡 The Solution: KARM CRED
KARM CRED is an alternative credit scoring platform designed specifically for the gig economy. By securely analyzing on-device data (SMS, UPI transaction frequency, and utility bill payments) in compliance with the DPDP Act, KARM CRED generates a fair and dynamic **TrustScore (300-900)**. 

We don't just provide a score; we provide transparency. Our AI engine explains exactly *why* a score was given, empowering users to understand and improve their financial health.

## 🛠️ Proposed Tech Stack
*   **Frontend (Mobile App):** Android / Kotlin (Simulates secure, read-only extraction of on-device SMS/API data and renders the user dashboard).
*   **Backend API:** Python / Flask (Securely accepts parsed data payloads and serves model predictions).
*   **Machine Learning Engine:** LightGBM (Processes synthetic data mapping transaction variability to creditworthiness).
*   **Explainable AI (XAI):** SHAP (Generates regulatory transparency insights to explain the TrustScore).

## ⚙️ Architecture Workflow
1.  **Data Extraction:** The mobile app securely reads local transactional data.
2.  **Risk Engine Processing:** Data is routed via the Flask API to the LightGBM model.
3.  **Score Generation & Explanation:** The model calculates the TrustScore and SHAP generates the reason codes.
4.  **Dashboard Display:** The final score and actionable financial insights are displayed on the user's mobile dashboard.

## 👥 Team Quad Core
*   **Om** - Team Leader & AI/Backend Architecture 
*   **Tanmay** - Mobile Frontend Development
*   **Aryan** - Mobile Frontend Development
*   **Atharva** - Mobile Frontend Development

---
*Developed for CODEX 2026 - MUSA*
