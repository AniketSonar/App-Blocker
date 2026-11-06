"""
Train the "doomscroll risk" model from the app's events.csv.   Run on your COMPUTER.

    pip install pandas numpy scikit-learn
    python train_risk_model.py events.csv

WHAT THE MODEL LEARNS
    Input : the situation at the moment a sitting in an app STARTS (time of day, how much
            you already used distracting apps today, how many apps you just switched between...)
    Output: the chance that this sitting will last 20+ minutes ("doomscroll risk").

WHY IT TRAINS ON THE APP'S OWN LOGS
    The app writes the exact numbers it fed to the model (event "risk_start") and, later, how
    long the sitting really lasted (event "session_end"). Training on the same numbers the
    phone will use means there is no mismatch between training and real use.

WHAT THE SCRIPT REFUSES TO DO
    * train with too little data
    * ship a model that is not clearly better than guessing
    * learn from sessions where the app itself interrupted you (that would teach the
      model about its own interventions, not about you)

OUTPUT: risk_model.json  -> copy to  app/src/main/assets/  and rebuild the app.
"""
import argparse
import json
import math
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, roc_auc_score

# THE ORDER MATTERS. It must be identical to RiskModel.FEATURES in the Kotlin code.
FEATURES = [
    "hour_sin", "hour_cos", "dow_sin", "dow_cos", "is_distracting",
    "min_since_last_session", "apps_last_10min",
    "sessions_so_far_today", "distracting_min_so_far_today",
]

MIN_SESSIONS = 150      # finished sessions needed before training
MIN_PER_CLASS = 20      # long and short sessions needed in the training part
MIN_TEST_AUC = 0.60     # below this the model is no better than a coin flip


def parse_detail(text):
    out = {}
    for part in str(text if isinstance(text, str) else "").split(";"):
        if "=" in part:
            key, value = part.split("=", 1)
            out[key.strip()] = value.strip()
    return out


def load_sessions(path):
    ev = pd.read_csv(path)
    ev["info"] = ev["detail"].map(parse_detail)

    ends, decisions = {}, {}
    for r in ev[ev["event"] == "session_end"].itertuples():
        ends[(r.package, r.info.get("start_ms"))] = r.info
    for r in ev[ev["event"] == "risk_decision"].itertuples():
        decisions[(r.package, r.info.get("start_ms"))] = r.info

    rows = []
    for r in ev[ev["event"] == "risk_start"].itertuples():
        key = (r.package, r.info.get("start_ms"))
        end = ends.get(key)
        if end is None:
            continue                                  # sitting not finished yet
        if not all(f in r.info for f in FEATURES):
            continue
        row = {f: float(r.info[f]) for f in FEATURES}
        row["start_ms"] = int(r.info["start_ms"])
        row["package"] = r.package
        row["dur_sec"] = float(end["dur_sec"])
        row["shown"] = int(decisions.get(key, {}).get("shown", 0))
        rows.append(row)
    return pd.DataFrame(rows)


def pick_threshold(p, y):
    """Flag as few sessions as possible while being right at least half the time."""
    best = None
    for t in np.unique(np.quantile(p, np.linspace(0.50, 0.95, 46))):
        flag = p >= t
        if flag.sum() == 0:
            continue
        precision, share = y[flag].mean(), flag.mean()
        recall = y[flag].sum() / max(1, y.sum())
        if precision >= 0.5 and share <= 0.35 and (best is None or recall > best[3]):
            best = (float(t), float(precision), float(share), float(recall))
    if best is None:
        t = float(np.quantile(p, 0.80))
        flag = p >= t
        best = (t, float(y[flag].mean()), float(flag.mean()), float(y[flag].sum() / max(1, y.sum())))
        print("  (no threshold reached 50% precision, using the 80th percentile instead)")
    return best


def sigmoid(z):
    return 1.0 / (1.0 + math.exp(-z))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("events_csv")
    ap.add_argument("--minutes", type=int, default=20, help="a sitting is 'long' from this many minutes")
    ap.add_argument("--out", default="risk_model.json")
    ap.add_argument("--force", action="store_true", help="write the model even if the safety checks fail")
    args = ap.parse_args()

    df = load_sessions(args.events_csv)
    print(f"finished sessions found: {len(df)}")
    if len(df) == 0:
        sys.exit("No finished sessions yet. Keep the app running (accessibility service ON) and try again later.")

    # Do not learn from sessions that the app itself interrupted.
    df = df[df["shown"] == 0].sort_values("start_ms").reset_index(drop=True)
    df["y"] = (df["dur_sec"] >= args.minutes * 60).astype(int)
    print(f"usable sessions (no app interruption): {len(df)}   long ({args.minutes}+ min): {int(df['y'].sum())}")

    # Time-based split: train on the past, test on the most recent 25% (like real life).
    cut = int(len(df) * 0.75)
    train, test = df.iloc[:cut], df.iloc[cut:]
    if len(df) < MIN_SESSIONS or train["y"].sum() < MIN_PER_CLASS or (len(train) - train["y"].sum()) < MIN_PER_CLASS:
        msg = (f"Not enough data yet: need at least {MIN_SESSIONS} sessions with {MIN_PER_CLASS}+ long "
               f"and {MIN_PER_CLASS}+ short ones in the training part. Keep collecting.")
        if not args.force:
            sys.exit(msg)
        print("WARNING (forced):", msg)
    if test["y"].nunique() < 2:
        sys.exit("The test part has only one kind of session. Collect more data.")

    X_tr, X_te = train[FEATURES].values, test[FEATURES].values
    mean = X_tr.mean(axis=0)
    std = X_tr.std(axis=0)
    std[std < 1e-6] = 1.0                      # constant feature: avoid dividing by zero

    clf = LogisticRegression(max_iter=2000, C=1.0)
    clf.fit((X_tr - mean) / std, train["y"])
    p_te = clf.predict_proba((X_te - mean) / std)[:, 1]
    y_te = test["y"].values

    auc = roc_auc_score(y_te, p_te)
    base_rate = float(train["y"].mean())
    brier = brier_score_loss(y_te, p_te)
    brier_base = brier_score_loss(y_te, np.full(len(y_te), base_rate))
    # A simple rule to beat: "distracting apps are riskier".
    rule_auc = roc_auc_score(y_te, test["is_distracting"]) if test["is_distracting"].nunique() > 1 else 0.5

    print("\n--- TEST RESULTS (most recent 25% of sessions) ---")
    print(f"AUC model        : {auc:.3f}   (0.5 = coin flip, 1.0 = perfect)")
    print(f"AUC simple rule  : {rule_auc:.3f}   (is_distracting only)")
    print(f"Brier model      : {brier:.4f}  vs always-guess-base-rate {brier_base:.4f}  (lower is better)")

    ok = auc >= MIN_TEST_AUC and auc > rule_auc and brier < brier_base
    if not ok and not args.force:
        sys.exit("\nThe model is not clearly better than simple guessing. NOT written. "
                 "Collect more data (or use --force to write it anyway for testing).")

    t, precision, share, recall = pick_threshold(p_te, y_te)
    print(f"\nthreshold {t:.2f}: flags {share*100:.0f}% of sessions, right {precision*100:.0f}% of the "
          f"time, catches {recall*100:.0f}% of long sessions")

    # Final model: same settings, trained on everything we have.
    X_all = df[FEATURES].values
    mean_f, std_f = X_all.mean(axis=0), X_all.std(axis=0)
    std_f[std_f < 1e-6] = 1.0
    final = LogisticRegression(max_iter=2000, C=1.0).fit((X_all - mean_f) / std_f, df["y"])
    weights, bias = final.coef_[0], float(final.intercept_[0])

    # A test example the phone re-computes when it loads the file (proves both sides agree).
    x0 = X_all[len(X_all) // 2]
    z = bias + sum(w * (v - m) / s for w, v, m, s in zip(weights, x0, mean_f, std_f))

    model = {
        "version": "lr-" + datetime.now(timezone.utc).strftime("%Y%m%d"),
        "trained": True,
        "label": f"sitting lasts {args.minutes}+ minutes",
        "features": FEATURES,
        "mean": [round(float(v), 8) for v in mean_f],
        "std": [round(float(v), 8) for v in std_f],
        "weights": [round(float(v), 8) for v in weights],
        "bias": round(bias, 8),
        "threshold": round(t, 4),
        "check": {"x": [round(float(v), 8) for v in x0], "p": round(sigmoid(z), 8)},
        "metrics": {"auc_test": round(float(auc), 4), "auc_rule": round(float(rule_auc), 4),
                    "n_sessions": int(len(df)), "long_share": round(float(df["y"].mean()), 4)},
    }
    with open(args.out, "w") as f:
        json.dump(model, f, indent=2)

    print(f"\nWrote {args.out}")
    print("What the model learned (positive = raises the risk of a long sitting):")
    for name, w in sorted(zip(FEATURES, weights), key=lambda kv: -abs(kv[1])):
        print(f"  {name:32s} {w:+.3f}")
    print("\nNext: copy risk_model.json to app/src/main/assets/ and rebuild the app.")


if __name__ == "__main__":
    main()
