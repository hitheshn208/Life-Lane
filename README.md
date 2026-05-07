<!--
  README (rewritten)
  Project: Life Lane
  Built at: Build for Bangalore (24hr hackathon)
-->

<p align="center">
  <img src="Logo_Reveal.gif" width="160" alt="Life Lane logo" />
</p>

<h1 align="center">Life Lane</h1>
<p align="center"><b>A faster path for ambulances through city traffic.</b></p>
---

## What is Life Lane?
In urban traffic, emergency vehicles lose valuable time at signals and congested junctions. **Life Lane** is our hackathon-built system that aims to **reduce ambulance delays** by enabling a **priority “green lane” flow**—so an ambulance can move through key intersections with fewer stops and smoother passage.

Built in **24 hours** at **Build for Bangalore**.

---

## How it works (Project Flow)
```text
Ambulance Driver App
   |
   | 1) Start Emergency Trip + Destination
   | 2) Continuously share live GPS location
   v
Backend (Node/Express)
   |
   | 3) Calculate/refresh ETA & next-signal context
   | 4) Send priority events to the control view
   v
Traffic Control Dashboard (Web)
   |
   | 5) Visualize trip status + coordinate priority flow
   v
(Concept) Signals aligned to form a "Life Lane"
```

### Flow in short
- **Activate**: Driver starts an emergency trip and selects destination.
- **Track**: App streams location updates to the backend.
- **Predict**: Backend keeps route/ETA updated.
- **Coordinate**: Dashboard reflects the trip and supports quick action/visibility for priority handling.

---

## Repo Structure
This repository contains three main parts: the **Android app** used by the ambulance/driver, the **backend API** that receives live updates and coordinates the flow, and the **web dashboard** for monitoring/coordination.

```text
.
├── App/
│   └── (Android application)
│       ├── Purpose:
│       │   - Driver/ambulance-side interface to start an emergency trip
│       │   - Sends live GPS updates to the backend
│       │   - Captures destination / emergency context
│       └── Notes:
│           - This is the Kotlin/Android Studio project
│       
├── backend/
│   └── (Node.js + Express backend)
│       ├── Purpose:
│       │   - Receives GPS/location updates from the mobile app
│       │   - Maintains trip state (active trip, latest coordinates)
│       │   - Handles API communication between app and dashboard
│       └── Notes:
│           - Acts as the "central brain" that can later be extended
│             for smarter routing / signal coordination
│       
├── Front End (web page)/
│   └── (Web dashboard)
│       ├── Purpose:
│       │   - Dashboard UI for viewing emergency trip status
│       │   - Monitoring + coordination interface (web-based)
│       │   
│       ├── Key files:
│       │   ├── index.html
│       │   ├── DashBoardScript.js
│       │   ├── DashBoardStyle.css
│       │   ├── WebPage2.html
│       │   ├── WebPage2Script.js
│       │   ├── WebPage2Style.css
│       │   ├── Logo.jpeg
│       │   ├── LogoBG.png
│       │   └── assets/
│       │
│       └── Notes:
│           - Pure HTML/CSS/JS style dashboard for quick hackathon delivery
│       
├── Logo_Reveal.gif
├── Logo_Reveal.mp4
└── README.md
```

---

## Key Highlights
- Real-time ambulance tracking
- Simple, fast-to-deploy stack (hackathon-ready)
- Designed around *time-critical* emergency response

---

## Tech Stack
<p align="center">
  <img src="https://skillicons.dev/icons?i=kotlin,androidstudio,nodejs,express,html,css,sqlite" />
</p>

- **Android App:** Kotlin (Android Studio)
- **Backend API:** Node.js + Express
- **Dashboard:** HTML + CSS
- **Database:** SQLite3

---

## Team
(From the original README)

| Member | Role |
| --- | --- |
| **N Hithesh Kumar** | Team Leader • Backend Development • Database Architecture |
| **Shyamanth Shetty** | Frontend Development • UI/UX Design |
| **Amith Braggs** | Mobile Application Development |
| **Alston Prince** | Mobile Application Development |

---

## Demo
_Add links/screenshots here when ready (video, APK, dashboard preview, etc)._
