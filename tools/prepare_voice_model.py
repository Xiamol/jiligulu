"""Fetch the pinned, Apache-2.0 Vosk small Chinese model for the offline trial build."""
import hashlib
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
TARGET = ROOT / "app/src/main/assets/offline_voice/model.zip"

def main():
    if TARGET.exists() and hashlib.file_digest(TARGET.open("rb"), "sha256").hexdigest() == SHA256:
        print("Verified offline model is already present.")
        return
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    temporary = TARGET.with_suffix(".zip.download")
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(URL, timeout=90) as response, temporary.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > 64 * 1024 * 1024:
                raise ValueError("Model exceeds download size limit")
            output.write(chunk)
            digest.update(chunk)
    if digest.hexdigest() != SHA256:
        raise ValueError("Model checksum mismatch; existing model was not changed")
    temporary.replace(TARGET)
    print(f"Model ready: {total} bytes; SHA-256 verified.")

if __name__ == "__main__":
    main()
