import time

from crunchometer.earnings import EarningsTracker, wallet_total

HOUR = 3600


def tracker(tmp_path, **kw):
    return EarningsTracker(str(tmp_path / "s.json"), kw.get("window", 24), kw.get("jump", 50))


def test_steady_earning(tmp_path):
    t = tracker(tmp_path)
    for m in range(0, 121):  # 2 hours, one sample a minute, 0.5 GRC/hour
        t.add(1000 + 0.5 * m / 60, now=m * 60)
    r = t.rate(now=120 * 60)
    assert abs(r["per_hour"] - 0.5) < 1e-6
    assert abs(r["per_day"] - 12) < 1e-3
    assert r["window_hours"] == 2 and r["skipped_jumps"] == 0


def test_stake_keeps_total_continuous():
    # Pending reward moves into the balance when a block is staked; total is unchanged.
    before = {"balance": 100, "staked": 0, "immature": 0, "unconfirmed": 0, "pending_research_reward": 30}
    after = {"balance": 70, "staked": 60, "immature": 0, "unconfirmed": 0, "pending_research_reward": 0}
    assert wallet_total(before) == wallet_total(after) == 130


def test_transfer_is_not_earnings(tmp_path):
    t = tracker(tmp_path)
    for m in range(0, 61):
        total = 1000 + m / 60          # 1 GRC/hour
        if m >= 30:
            total -= 500               # sent 500 GRC away halfway through
        t.add(total, now=m * 60)
    r = t.rate(now=60 * 60)
    # The skipped step also held one minute of earnings, so slightly under 1.
    assert abs(r["per_hour"] - 59 / 60) < 1e-6
    assert r["skipped_jumps"] == 1


def test_needs_ten_minutes(tmp_path):
    t = tracker(tmp_path)
    for m in range(5):
        t.add(1000 + m, now=m * 60)
    assert t.rate(now=4 * 60) is None


def test_window_and_persistence(tmp_path):
    # Real timestamps: a reloaded tracker prunes by the real clock.
    start = time.time() - 3 * HOUR
    t = tracker(tmp_path, window=1)
    for m in range(0, 181):  # 3 hours: 2 GRC/h, then 4 GRC/h for the last hour
        rate = 2 if m <= 120 else 4
        prev = t.samples[-1][1] if t.samples else 1000
        t.add(prev + rate / 60 if m else 1000, now=start + m * 60)
    t.save()
    r = EarningsTracker(str(tmp_path / "s.json"), 1, 50).rate(now=start + 180 * 60)
    assert abs(r["per_hour"] - 4) < 1e-6  # only the last hour counts


def test_no_balance_no_total():
    assert wallet_total(None) is None
    assert wallet_total({"balance": None, "pending_research_reward": 5}) is None
