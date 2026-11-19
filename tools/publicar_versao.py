"""Publica o APK de release no servidor (aparece no painel em Atualizacoes e chega aos usuarios).

Uso:
  python tools/publicar_versao.py --notas "O que mudou" [--obrigatoria] [--apk caminho.apk] [--escondida]

Le versionCode/versionName de app/build.gradle.kts, calcula o SHA-256, envia direto para o S3
por link temporario e libera a versao. --escondida envia sem liberar (voce libera no painel).
"""
import argparse
import hashlib
import json
import pathlib
import re
import sys
import urllib.request

API = "https://d07z412uu9.execute-api.sa-east-1.amazonaws.com"
ROOT = pathlib.Path(__file__).resolve().parents[1]


def admin(path, body, token):
    req = urllib.request.Request(API + "/admin/api/" + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Admin-Token": token})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.load(e)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--notas", required=True)
    ap.add_argument("--obrigatoria", action="store_true")
    ap.add_argument("--escondida", action="store_true")
    ap.add_argument("--apk", default=str(ROOT / "app/build/outputs/apk/release/app-release.apk"))
    a = ap.parse_args()

    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    apk = pathlib.Path(a.apk)
    data = apk.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    token = (ROOT / "server/secrets/admin_token.txt").read_text().strip()
    print(f"Versão {name} ({code}) · {len(data) / 1048576:.1f} MB · {'OBRIGATÓRIA' if a.obrigatoria else 'opcional'}")

    j = admin("version-new", {"versionCode": code, "versionName": name, "notes": a.notas,
                              "mandatory": a.obrigatoria, "sha256": sha, "replace": True}, token)
    if not j.get("ok"):
        sys.exit("Erro: " + j.get("message", str(j)))
    put = urllib.request.Request(j["uploadUrl"], data=data, method="PUT")
    with urllib.request.urlopen(put, timeout=600) as r:
        if r.status >= 300:
            sys.exit(f"Falha no envio ({r.status})")
    j = admin("version-done", {"versionCode": code, "sha256": sha, "active": not a.escondida}, token)
    if not j.get("ok"):
        sys.exit("Erro: " + j.get("message", str(j)))
    print("Publicada" + (" (escondida: libere no painel)" if a.escondida else " e liberada para os usuários."))


if __name__ == "__main__":
    main()
