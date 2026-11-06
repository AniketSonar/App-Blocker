"""
Makes a FAKE events.csv so you can test train_risk_model.py before you have real data.
The numbers are invented. NEVER ship a model trained on this file.

    python make_fake_events.py          ->  fake_events.csv
    python train_risk_model.py fake_events.csv
"""
import math
import random

random.seed(7)
rows = ["timestamp_ms,event,package,session_min,hour,day_of_week,detail"]
t0 = 1_760_000_000_000
apps = [("com.instagram.android", 1), ("com.google.android.youtube", 0), ("com.android.chrome", 0)]

for i in range(700):
    pkg, distracting = random.choice(apps)
    hour = random.choice([8, 9, 12, 13, 18, 19, 20, 21, 22, 23, 0, 1])
    dow = random.randint(1, 7)
    start = t0 + i * 3_600_000
    f = {
        "hour_sin": math.sin(2 * math.pi * hour / 24), "hour_cos": math.cos(2 * math.pi * hour / 24),
        "dow_sin": math.sin(2 * math.pi * dow / 7), "dow_cos": math.cos(2 * math.pi * dow / 7),
        "is_distracting": distracting,
        "min_since_last_session": random.choice([0, 1, 3, 10, 40, 120]),
        "apps_last_10min": random.randint(0, 5),
        "sessions_so_far_today": random.randint(0, 25),
        "distracting_min_so_far_today": random.randint(0, 240),
    }
    # Invented "truth": night + distracting + already a lot today -> longer sittings.
    z = (-2.2 + 1.4 * distracting + 0.9 * f["hour_cos"] + 0.012 * f["distracting_min_so_far_today"]
         - 0.15 * f["apps_last_10min"])
    long_session = random.random() < 1 / (1 + math.exp(-z))
    dur = random.uniform(1260, 3000) if long_session else random.uniform(20, 1100)
    detail = f"start_ms={start};p=0.5000;model=prior-v0;trained=0;" + ";".join(f"{k}={v:.4f}" for k, v in f.items())
    rows.append(f"{start},risk_start,{pkg},0,{hour},{dow},{detail}")
    rows.append(f"{start + int(dur * 1000)},session_end,{pkg},{int(dur // 60)},{hour},{dow},start_ms={start};dur_sec={int(dur)}")

with open("fake_events.csv", "w") as out:
    out.write("\n".join(rows) + "\n")
print("wrote fake_events.csv with", (len(rows) - 1) // 2, "sessions")
