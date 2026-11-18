"""
Analisa os registros (logs) do Filtro Inteligente para achar bugs e pontos de melhoria.

Uso:
    python tools/analisar_logs.py                # baixa do celular (USB) e analisa o que e NOVO desde a ultima analise
    python tools/analisar_logs.py --tudo         # analisa todos os registros guardados no celular
    python tools/analisar_logs.py --se-conectado # so faz algo se o celular estiver ligado no USB (para rodar de hora em hora)
    python tools/analisar_logs.py --pasta X      # analisa uma pasta ja baixada (sem celular)

Os registros baixados ficam em logs_celular/<data_hora>/ (o celular so guarda ~20 MB e vai apagando os antigos),
junto com o relatorio.txt. A posicao da ultima analise fica em logs_celular/.ultima_analise.json.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta

PKG = "com.ruan.uberareafilter"
REMOTE_DIR = f"/sdcard/Android/data/{PKG}/files"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_ROOT = os.path.join(ROOT, "logs_celular")
STATE_FILE = os.path.join(OUT_ROOT, ".ultima_analise.json")

TS_RE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] ?(.*)$")
DAY_RE = re.compile(r"^===== (\d\d)/(\d\d)/(\d{4})")


# ------------------------------------------------------------------ adb
def adb_path():
    local = os.environ.get("LOCALAPPDATA", "")
    cand = os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe")
    return cand if os.path.exists(cand) else "adb"


def adb(*args, timeout=60):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    r = subprocess.run([adb_path(), *args], capture_output=True, text=True, timeout=timeout,
                       env=env, encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def connected_device():
    code, out = adb("devices")
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


def remote_signature(serial):
    """Tamanho + data de cada arquivo de log no celular (para nao baixar de novo se nada mudou)."""
    code, out = adb("-s", serial, "shell", f"ls -l {REMOTE_DIR}")
    lines = [l for l in out.splitlines() if "filtro_inteligente_logs" in l]
    return "|".join(" ".join(l.split()[4:8]) for l in sorted(lines))


def prune_old_pulls(keep=30):
    """Guarda so as ultimas 30 copias baixadas (cada uma tem ate ~20 MB)."""
    if not os.path.isdir(OUT_ROOT):
        return
    dirs = sorted(d for d in os.listdir(OUT_ROOT) if re.match(r"\d{4}-\d\d-\d\d_\d{4}$", d))
    import shutil
    for d in dirs[:-keep]:
        shutil.rmtree(os.path.join(OUT_ROOT, d), ignore_errors=True)


def pull_logs(serial):
    stamp = datetime.now().strftime("%Y-%m-%d_%H%M")
    dest = os.path.join(OUT_ROOT, stamp)
    os.makedirs(dest, exist_ok=True)
    code, out = adb("-s", serial, "shell", f"ls {REMOTE_DIR}")
    names = [n.strip() for n in out.split() if n.strip().startswith("filtro_inteligente_logs")]
    for n in names:
        adb("-s", serial, "pull", f"{REMOTE_DIR}/{n}", os.path.join(dest, n), timeout=120)
    code, ver = adb("-s", serial, "shell", f"dumpsys package {PKG} | grep versionName")
    with open(os.path.join(dest, "versao.txt"), "w", encoding="utf-8") as f:
        f.write(ver.strip() + "\n")
    return dest


# ------------------------------------------------------------------ leitura
def ordered_files(folder):
    """Do mais antigo (.10) ao atual."""
    files = []
    for n in os.listdir(folder):
        m = re.match(r"filtro_inteligente_logs(?:\.(\d+))?\.txt$", n)
        if m:
            idx = int(m.group(1)) if m.group(1) else 0
            files.append((idx, os.path.join(folder, n)))
    files.sort(key=lambda x: -x[0])
    return [p for _, p in files]


def read_events(folder):
    """Linhas com data/hora completas. Usa o cabecalho de data; sem ele, avanca o dia quando a hora volta."""
    events = []
    day = None
    last = None
    for path in ordered_files(folder):
        with open(path, encoding="utf-8", errors="replace") as f:
            for raw in f:
                line = raw.rstrip("\n")
                dm = DAY_RE.match(line)
                if dm:
                    day = datetime(int(dm.group(3)), int(dm.group(2)), int(dm.group(1)))
                    continue
                m = TS_RE.match(line)
                if not m:
                    if events:  # continuacao da linha anterior (checklist do card)
                        events[-1] = (events[-1][0], events[-1][1] + " ⏎ " + line.strip())
                    continue
                h, mi, s, text = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
                if day is None:
                    day = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=1)
                ts = day.replace(hour=h, minute=mi, second=s)
                if last is not None and ts < last - timedelta(hours=1):
                    day += timedelta(days=1)
                    ts = day.replace(hour=h, minute=mi, second=s)
                last = ts
                events.append((ts, text))
    return events


# ------------------------------------------------------------------ analise
def short(t, n=230):
    t = re.sub(r"\s+", " ", t)
    return t if len(t) <= n else t[:n] + "…"


def analyze(events):
    rep = defaultdict(list)
    counts = Counter()
    decisions = Counter()
    uncertain_reasons = Counter()
    reject_keys = Counter()
    recent_offers = []  # (ts, app, pickup, dropoff) das ultimas ofertas recusadas
    seen_unknown = set()  # telas nao reconhecidas ja listadas (uma vez cada)

    for i, (ts, t) in enumerate(events):
        hhmm = ts.strftime("%d/%m %H:%M:%S")

        if "=== SESSAO" in t:
            counts["sessoes"] += 1
            v = re.search(r"SESSAO (v[\d.]+ \(\d+\))", t)
            rep["Sessões (versão)"].append(f"{hhmm} {v.group(1) if v else ''}")
        if t.startswith("[SAIDA]"):
            if "atualização do app" not in t and "o próprio app encerrou" not in t:
                rep["⚠ App fechado pelo Android (não foi atualização nem OFF)"].append(f"{hhmm} {short(t)}")
        if t.startswith("[SERVICO]"):
            rep["⚠ Leitura de tela interrompida"].append(f"{hhmm} {short(t)}")
        if "⚠ ERRO" in t or "Exception" in t or "TRAVOU" in t or "Falha ao" in t or "⚠ Falha" in t:
            rep["❌ Erros"].append(f"{hhmm} {short(t)}")
        if t.startswith("[MARCA]"):
            ctx = [f"     {e[0].strftime('%H:%M:%S')} {short(e[1], 170)}" for e in events[max(0, i - 25):i]
                   if re.search(r"Decisao|\[CICLO\]|\[GANHOS\]|🔒|🔓|📌|🔄|Auto-|\[ENCAIXE\]|\[LIBERADA\]|NAO-RECONHECIDO", e[1])]
            rep["🐞 MARCAS do motorista (o que aconteceu antes)"].append(f"{hhmm} {short(t, 400)}\n" + "\n".join(ctx[-12:]))

        # ---- decisoes / ofertas
        dm = re.search(r"\] Decisao: (\w+) \((\d+)ms", t)
        if dm:
            decisions[dm.group(1)] += 1
            if int(dm.group(2)) > 2500:
                rep["🐢 Decisão demorada (> 2,5 s)"].append(f"{hhmm} {short(t, 160)}")
            if dm.group(1) == "UNCERTAIN":
                r = re.search(r"-> ⚠ ([^|]+)", t)
                if r:
                    uncertain_reasons[re.sub(r"\d", "#", r.group(1).strip())[:90]] += 1
        if "Auto-recusa cirurgica" in t:
            counts["recusas"] += 1
            n = re.search(r"#(\d+) disparada", t)
            if n and int(n.group(1)) >= 3:
                rep["⚠ Recusa precisou de 3+ tentativas"].append(f"{hhmm} {short(t)}")
        if "cancelado: janela mudou" in t or "cancelada: janela mudou" in t:
            rep["ℹ Toque cancelado (janela mudou no atraso)"].append(f"{hhmm} {short(t)}")
        if re.search(r"Auto-aceitar.*sucesso=false", t):
            rep["❌ Aceite automático falhou"].append(f"{hhmm} {short(t)}")
        if re.search(r"Auto-aceitar.*sucesso=true", t):
            counts["aceites"] += 1
        om = re.search(r"OCR \[(99|UBER)\][^(]*\(OFERTA[^)]*\).*Emb=(.*?) \| Dest=(.*)$", t)
        if om:
            app, emb, dest = om.group(1), om.group(2)[:40], om.group(3)[:40]
            for (pts, papp, pemb, pdest) in recent_offers:
                if papp == app and pemb == emb and pdest == dest and timedelta(seconds=20) < ts - pts < timedelta(minutes=4):
                    rep["ℹ Mesma corrida tocou de novo depois de recusada"].append(f"{hhmm} {app}: {emb} → {dest} (antes às {pts.strftime('%H:%M:%S')})")
                    break
        if "Auto-recusa cirurgica" in t:
            # liga a recusa a ultima oferta lida
            for j in range(i - 1, max(0, i - 12), -1):
                om2 = re.search(r"OCR \[(99|UBER)\][^(]*\(OFERTA[^)]*\).*Emb=(.*?) \| Dest=(.*)$", events[j][1])
                if om2:
                    recent_offers.append((ts, om2.group(1), om2.group(2)[:40], om2.group(3)[:40]))
                    recent_offers[:] = recent_offers[-40:]
                    break
        rm = re.search(r"-> ✘ ([^|]+)", t)
        if dm and rm:
            for part in rm.group(1).split("•"):
                k = re.split(r"[:(\d]", part.strip())[0].strip()
                if k:
                    reject_keys[k] += 1
        if "R$/km=N/A" in t and "(OFERTA" in t:
            counts["preco_nao_lido"] += 1
        if t.startswith("[NEGOCIA]"):
            rep["💬 Negocia"].append(f"{hhmm} {short(t, 260)}")
        # Card de corrida com "Aceitar" + "N min" + "R$" que o app NAO reconheceu = visual novo do app?
        if "[RAIO-X OCR-NAO-CASADO]" in t:
            items = re.findall(r':"([^"]*)"', t)
            has_accept = any(re.match(r"(?i)\s*aceitar(?! se tocar)", s) for s in items)
            has_leg = any(re.search(r"\d\s*min", s) for s in items)
            has_price = any("R$" in s for s in items)
            if has_accept and has_leg and has_price:
                legs = [s for s in items if re.search(r"\d\s*min", s)][:2]
                rep["❌ Card de corrida NÃO lido (visual novo do app?)"].append(f"{hhmm} trechos: {' / '.join(legs)}")

        # ---- ganhos / ciclo
        if "[GANHOS]" in t:
            if "registrada" in t:
                counts["corridas"] += 1
                if "R$ ?" in t:
                    rep["❌ Corrida gravada sem valor"].append(f"{hhmm} {short(t)}")
                if "estimado" in t:
                    rep["ℹ Valor estimado pelo R$/hora (preço ilegível)"].append(f"{hhmm} {short(t)}")
            if "CANCELADA" in t:
                counts["canceladas"] += 1
                # o texto lido na tela no momento (para conferir se foi cancelamento de verdade)
                ctx = ""
                for j in range(i, max(0, i - 8), -1):
                    cm = re.findall(r'"([^"]*[Cc]ancel[^"]*)"', events[j][1])
                    if cm:
                        ctx = " | tela: " + " / ".join(cm[:2])
                        break
                rep["🔎 Cancelamentos (confira se foram de verdade)"].append(f"{hhmm} {short(t, 170)}{ctx}")
            if "após 0 min" in t or "após 1 min" in t:
                if "CONCLUÍDA" in t:
                    rep["⚠ Corrida concluída rápido demais (0-1 min)"].append(f"{hhmm} {short(t)}")
            if "fim não visto" in t:
                rep["⚠ Corrida fechada sem ver o fim"].append(f"{hhmm} {short(t)}")
            if "sem oferta lida" in t:
                rep["ℹ Em viagem sem oferta lida (aceite fora do app?)"].append(f"{hhmm} {short(t)}")
            if "fica na fila" in t:
                rep["ℹ Corrida aceita durante outra (fila)"].append(f"{hhmm} {short(t)}")
            if "Retomei" in t:
                rep["ℹ Corridas retomadas ao religar"].append(f"{hhmm} {short(t)}")
        if t.startswith("🔓") and "LIBERADA manualmente" in t:
            rep["⚠ Você soltou a trava na mão (o app não percebeu o fim/cancelamento?)"].append(f"{hhmm} {short(t)}")
        if t.startswith("🔒") and "Corrida ativa detectada" in t:
            rep["ℹ Trava criada pela tela de viagem (sem aceite pelo app)"].append(f"{hhmm} {short(t)}")
        if t.startswith("[ENCAIXE]"):
            rep["⛓ Encaixe 99/Uber"].append(f"{hhmm} {short(t, 260)}")
        if t.startswith("[LIBERADA]"):
            rep["🔁 Aceitar se tocar de novo"].append(f"{hhmm} {short(t)}")
        if t.startswith("[BLOQUEIO]"):
            rep["🧭 Bloqueio pelo endereço"].append(f"{hhmm} {short(t)}")
        if t.startswith("[ZONAS]"):
            rep["🗺 Zonas enviadas/recebidas"].append(f"{hhmm} {short(t)}")
        if t.startswith("[TURNO]"):
            rep["⏱ Turno (horas online)"].append(f"{hhmm} {short(t)}")
        if "[OFF] Religado" in t:
            counts["religadas_off"] += 1

        # ---- telas nao reconhecidas com cara de etapa da corrida
        if t.startswith("OCR [NAO-RECONHECIDO]"):
            low = t.lower()
            stats_screen = re.search(r"taxa de finaliza|dados diários|corridas finalizadas|últimos 30 dias|trava liberada", low)
            if not stats_screen and re.search(r"cancel|finalizar|avali|offline|online|você cancelou|motivo|chegou|iniciar", low):
                body = re.sub(r"^.*?\): ", "", t)
                sig = re.sub(r"\d", "", body.lower())[:70]
                if sig not in seen_unknown:
                    seen_unknown.add(sig)
                    rep["🆕 Tela não reconhecida com cara de etapa (ensinar ao app?)"].append(f"{hhmm} {short(body, 300)}")

        # ---- saude a cada 10 min
        if t.startswith("[STATUS]"):
            ocr = re.search(r"OCR médio (\d+)ms", t)
            if ocr and int(ocr.group(1)) > 600:
                rep["🐢 Leitura lenta (OCR > 600 ms)"].append(f"{hhmm} {short(t, 200)}")
            if re.search(r"calor=(QUENTE|MUITO QUENTE|CRÍTICO)", t):
                rep["🔥 Celular esquentando"].append(f"{hhmm} {short(t, 200)}")
            mem = re.search(r"memória=(\d+)MB", t)
            if mem and int(mem.group(1)) > 250:
                rep["⚠ Memória alta"].append(f"{hhmm} {short(t, 200)}")
            bat = re.search(r"bateria=(\d+)%(?! carregando)", t)
            if bat and int(bat.group(1)) <= 15:
                rep["🔋 Bateria baixa"].append(f"{hhmm} {short(t, 200)}")

    return rep, counts, decisions, uncertain_reasons, reject_keys


def build_report(events, since):
    sel = [e for e in events if since is None or e[0] > since]
    if not sel:
        return "Nada novo nos registros desde a última análise.\n", None
    rep, counts, decisions, unc, rej = analyze(sel)
    out = []
    first, last = sel[0][0], sel[-1][0]
    out.append(f"RELATÓRIO DO FILTRO INTELIGENTE — {first:%d/%m %H:%M} até {last:%d/%m %H:%M} ({len(sel)} linhas)")
    out.append("=" * 78)
    out.append(
        f"Ofertas avaliadas: {sum(decisions.values())} | corridas gravadas: {counts['corridas']} | "
        f"canceladas: {counts['canceladas']} | aceites automáticos: {counts['aceites']} | "
        f"recusas: {counts['recusas']} | preço não lido: {counts['preco_nao_lido']} | "
        f"religadas pelo OFF: {counts['religadas_off']}"
    )
    if decisions:
        out.append("Decisões: " + ", ".join(f"{k}={v}" for k, v in decisions.most_common()))
    if rej:
        out.append("Motivos de recusa mais comuns: " + ", ".join(f"{k} ({v})" for k, v in rej.most_common(8)))
    if unc:
        out.append("Locais incertos mais comuns: " + "; ".join(f"{k} ({v})" for k, v in unc.most_common(6)))
    order = [k for k in rep if k.startswith("🐞")] + [k for k in rep if k.startswith("❌")] + \
            [k for k in rep if k.startswith("⚠")] + [k for k in rep if k.startswith("🔎")] + \
            [k for k in rep if k.startswith("🆕")] + \
            [k for k in rep if not k[0] in "🐞❌⚠🔎🆕"]
    seen = set()
    for k in order:
        if k in seen:
            continue
        seen.add(k)
        items = rep[k]
        out.append("")
        out.append(f"## {k} — {len(items)}")
        limit = 30 if k.startswith(("🐞", "❌", "🔎")) else 12
        for it in items[:limit]:
            out.append("  " + it)
        if len(items) > limit:
            out.append(f"  … mais {len(items) - limit}")
    return "\n".join(out) + "\n", last


def read_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def load_state():
    try:
        return datetime.fromisoformat(read_state()["ate"])
    except Exception:
        return None


def save_state(ts, signature=None):
    os.makedirs(OUT_ROOT, exist_ok=True)
    st = read_state()
    if ts is not None:
        st["ate"] = ts.isoformat()
    if signature is not None:
        st["assinatura"] = signature
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(st, f)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--tudo", action="store_true")
    ap.add_argument("--se-conectado", action="store_true")
    ap.add_argument("--pasta")
    a = ap.parse_args()

    signature = None
    if a.pasta:
        folder = a.pasta
    else:
        serial = connected_device()
        if not serial:
            print("CELULAR DESCONECTADO")
            return 0
        signature = remote_signature(serial)
        if a.se_conectado and signature and signature == read_state().get("assinatura"):
            print("SEM NOVIDADE (os registros do celular não mudaram desde a última análise)")
            return 0
        folder = pull_logs(serial)
        prune_old_pulls()
    events = read_events(folder)
    since = None if a.tudo else load_state()
    report, last = build_report(events, since)
    with open(os.path.join(folder, "relatorio.txt"), "w", encoding="utf-8") as f:
        f.write(report)
    print(f"(registros em {folder})")
    print(report)
    if not a.pasta:
        save_state(last, signature)
    return 0


if __name__ == "__main__":
    sys.exit(main())
