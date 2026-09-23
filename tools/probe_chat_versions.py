"""Compare release/current request shapes using synthetic conversations only.

Reads the existing local build key in memory. Never logs credentials, request headers,
phone conversations or provider error bodies. Results contain only fixture responses.
"""
import concurrent.futures
import argparse
import datetime
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8")

def source(ref, path):
    if ref == "current":
        return (ROOT / path).read_text(encoding="utf-8")
    return subprocess.check_output(["git", "show", f"{ref}:{path}"], cwd=ROOT).decode("utf-8")

def messages(mode, phrase, supplied_history=None):
    fixture = {"nickname": "验收用户", "suffix": "", "address": "验收用户",
        "now": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "timezone": "Asia/Shanghai",
        "categories": "- 吃饭（关键词：饭,面）\n- 饮品（关键词：水,茶）\n- 待定（关键词：其他）",
        "history": "（这是你们第一次说话）", "bills": "（最近三天还没有记过账）",
        "pending": "（没有待补充的账）", "candidates": "", "trashCandidates": "", "otherBills": "",
        "appSettings": "【当前提醒设置】\n喝水提醒：关闭；间隔：30 分钟；免打扰：关闭。", "input": phrase}
    def render(template):
        for key, value in fixture.items():
            template = template.replace("{" + key + "}", value)
        return template
    old = mode in ("v0.5.4", "v0.5.5")
    history = [] if mode == "v0.5.4" else [
        {"role": "user", "content": "今天中午吃了面，味道不错"},
        {"role": "assistant", "content": "听起来挺香呀，吃饱了才有精神，阿噜 ♡"}]
    if supplied_history is not None: history = [dict(turn) for turn in supplied_history]
    if mode == "v0.5.5":
        # The shipped version injects history into system too and retains the just-sent user row.
        history.append({"role": "user", "content": phrase})
        fixture["history"] = "\n".join(("用户" if turn["role"] == "user" else "阿噜") + "：" + turn["content"] for turn in history)
        fixture["candidates"] = "（候选账单为空）"
    system = render(source(mode, "app/src/main/assets/prompts/parse_bill.txt")) if old else source("current", "app/src/main/assets/prompts/parse_bill_system.txt")
    if "json_history" in mode:
        for turn in history:
            if turn["role"] == "assistant":
                turn["content"] = json.dumps({"bills": [], "reply": turn["content"]}, ensure_ascii=False, separators=(",", ":"))
    user = phrase if old else render(source("current", "app/src/main/assets/prompts/parse_bill_context.txt"))
    if mode == "current_no_history": history = []
    if "hint" in mode: user += '\n请只返回符合应用协议的 JSON 对象；闲聊示例：{"bills":[],"reply":"在呀，阿噜在听。"}。不要返回空白、代码或协议说明。'
    return [{"role": "system", "content": system}, *history, {"role": "user", "content": user}]

def probe(key, mode, phrase, history=None):
    payload = {"model": "deepseek-flash", "temperature": 0.7, "response_format": {"type": "json_object"}, "messages": messages(mode, phrase, history)}
    if "text" in mode: payload.pop("response_format")
    request = urllib.request.Request("https://api.deepseek.com/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    start = time.monotonic()
    record = {"mode": mode, "input": phrase}
    try:
        with urllib.request.urlopen(request, timeout=75) as response:
            data = json.loads(response.read())
            record["http"] = response.status
        choice = (data.get("choices") or [{}])[0]
        content = (choice.get("message") or {}).get("content") or ""
        record.update(finish=choice.get("finish_reason"), chars=len(content), blank=not content.strip())
        if content.strip():
            try:
                parsed = json.loads(content.strip().removeprefix("```json").removesuffix("```").strip())
                record["valid"] = isinstance(parsed, dict) and isinstance(parsed.get("reply"), str) and bool(parsed["reply"].strip())
                record["reply"] = parsed.get("reply", "")[:100] if isinstance(parsed, dict) else "[non-object]"
                if isinstance(parsed, dict):
                    record["bill_count"] = len(parsed.get("bills") or [])
                    record["app_action"] = parsed.get("app_action")
            except Exception:
                record["valid"] = False
                record["fixture_output"] = content[:350]
        else: record["valid"] = False
    except urllib.error.HTTPError as error:
        record.update(http=error.code, valid=False)
    except Exception as error:
        record.update(error=type(error).__name__, valid=False)
    record["seconds"] = round(time.monotonic() - start, 2)
    return record

def main():
    key = next((line.partition("=")[2].strip() for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines()
                if line.strip().startswith("DEEPSEEK_API_KEY=")), "")
    if not key: raise SystemExit("Local build key unavailable")
    parser = argparse.ArgumentParser()
    parser.add_argument("modes", nargs="*", default=[])
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--actions", action="store_true")
    parser.add_argument("--conversation", action="store_true")
    parser.add_argument("--legacy-errors", action="store_true")
    args = parser.parse_args()
    modes = args.modes or ["v0.5.4", "v0.5.5", "current", "current_json_history"]
    cases = ["hi", "陪我唠唠嗑", "在嘛？", "那必须的"]
    if args.actions: cases = ["昨天中午吃饭花了9元", "水，3", "把喝水提醒改成45分钟", "检查更新"]
    if args.conversation:
        turns = []
        if args.legacy_errors:
            for phrase in ["hi", "陪我唠唠嗑", "那必须的"]:
                turns.extend([{"role": "user", "content": phrase},
                    {"role": "assistant", "content": "AI 服务这次返回了空内容，阿噜没接到回复，请稍后再试。"}])
        for phrase in ["hi", "陪我唠唠嗑", "我刚才出去散步了", "那必须的", "走了一大圈，有点累", "你记得我刚才去干嘛了吗？", "在嘛？", "好了，我先去休息一会儿"]:
            result = probe(key, modes[0], phrase, turns)
            print(json.dumps(result, ensure_ascii=False), flush=True)
            turns.append({"role": "user", "content": phrase})
            if result.get("valid"): turns.append({"role": "assistant", "content": result["reply"]})
        return
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [pool.submit(probe, key, mode, phrase) for _ in range(args.repeat) for phrase in cases for mode in modes]
        for job in concurrent.futures.as_completed(jobs):
            print(json.dumps(job.result(), ensure_ascii=False), flush=True)

if __name__ == "__main__": main()
