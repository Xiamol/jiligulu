"""Offline cost scenarios, not production billing or a guaranteed cache rate."""
import json

RATES = (0.02, 1.0, 4.0)  # yuan per million tokens, provided by the user

def cost(hit, miss, output):
    return (hit * RATES[0] + miss * RATES[1] + output * RATES[2]) / 1_000_000

def scenario(batch, rounds=600, cold_every=None):
    # Alternating user/assistant ~40 tokens/message, stable prefix 2400,
    # dynamic context 600 tokens, output 80 tokens. Clock/ledger text stays uncached.
    previous = []
    totals = [0, 0, 0]
    max_history = 0
    for turn in range(rounds):
        count = turn * 2 + 1
        window = 60 if count <= 60 else 60 + (count - 60) % batch
        history = list(range(max(0, count - window), count - 1))
        reused = 0
        if previous and history and previous[0] == history[0]:
            reused = min(len(history), len(previous))
        hit = 2400 + reused * 40
        if turn == 0 or (cold_every and turn % cold_every == 0):
            hit = 0
        prompt = 2400 + 600 + len(history) * 40
        totals[0] += hit
        totals[1] += prompt - hit
        totals[2] += 80
        max_history = max(max_history, len(history))
        previous = history
    return dict(batch=batch, max_history=max_history, yuan_per_600_rounds=round(cost(*totals), 6),
                input_hit_percent=round(totals[0] / (totals[0] + totals[1]) * 100, 1))

if __name__ == "__main__":
    print(json.dumps({
        "assumptions": "600 rounds, 40 tokens/history message, stable prefix 2400, dynamic 600, output80; warm prefix reuse assumed",
        "continuous": [scenario(b) for b in [2,20,40,60,80,100,120,160,240]],
        "cold_every_10_rounds": [scenario(b,cold_every=10) for b in [20,40,60,80,100,120,160,240]],
        "illustration": {"10k_input_55pct_hit_300_output": cost(5500,4500,300),
                         "20k_input_90pct_hit_300_output":cost(18000,2000,300),
                         "100k_input_95pct_hit_300_output":cost(95000,5000,300)}
    },ensure_ascii=False,indent=2))
