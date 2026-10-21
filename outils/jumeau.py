#!/usr/bin/env python3
"""
Lance le jumeau numérique MCC du « Monsieur Cuisine Developer SDK » sans interface graphique,
pour tester Toque sans toucher à la vraie machine.

    git clone https://github.com/antrea/monsieur-cuisine-developer-sdk
    python jumeau.py --sdk chemin/vers/monsieur-cuisine-developer-sdk

Toque (mode « Jumeau numérique », hôte 127.0.0.1, port 8765) s'y connecte :
  - émulateur Android : hôte 10.0.2.2 ;
  - tablette ou téléphone : `adb reverse tcp:8765 tcp:8765` puis hôte 127.0.0.1.

Commandes au clavier (ou sur le port de contrôle 8770, une par ligne) :
  lid 1|0        ouvrir / fermer le couvercle      error N   code d'erreur (0 = aucune)
  temp C         forcer la température mesurée     weight G  poids sur la balance
  silent 1|0     base muette (perte de liaison)    corrupt N corrompre N réponses
  state          afficher l'état                   quit
"""
import argparse
import json
import os
import select
import socket
import sys
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sdk", default=os.environ.get("MC_SDK", "monsieur-cuisine-developer-sdk"))
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--control", type=int, default=8770)
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--send", help="envoyer une commande à un jumeau déjà lancé, ex. --send \"lid 1\"")
    args = ap.parse_args()
    if args.send:
        with socket.create_connection(("127.0.0.1", args.control), timeout=3) as s:
            s.sendall((args.send + "\n").encode())
            print(s.makefile().readline().strip())
        return
    sys.path.insert(0, os.path.join(args.sdk, "src"))
    from monsieur_cuisine_sdk import DigitalTwin
    from monsieur_cuisine_sdk.android_connection import AndroidConnection, ConnectionConfig

    twin = DigitalTwin("mcc", record=False)
    conn = AndroidConnection(twin, ConnectionConfig(port=args.port, idle_timeout=3600.0)).start()
    ctl = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ctl.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctl.bind(("127.0.0.1", args.control))
    ctl.listen(4)
    ctl.setblocking(False)
    clients = []
    print(f"Jumeau MCC prêt : Toque -> 127.0.0.1:{conn.address[1]}, contrôle -> 127.0.0.1:{args.control}", flush=True)

    def summary():
        s = twin.get_state()
        return json.dumps({
            "rpm": s.actual_rpm, "temperature": s.temperature, "target_temperature": s.target_temperature,
            "motor_on": s.motor_on, "heater_on": s.heater_on, "lid_open": s.lid_open,
            "error_code": s.error_code, "weight": s.weight, "direction": s.actual_direction,
            "connection": conn.status()["status"], "tx": conn.tx_frames, "rx": conn.rx_frames,
        })

    def command(line):
        parts = line.strip().split()
        if not parts:
            return ""
        op, arg = parts[0], (parts[1] if len(parts) > 1 else "0")
        try:
            if op == "lid":
                twin.set_sensors(lid_open=arg == "1")
            elif op == "error":
                twin.set_sensors(error_code=int(arg))
            elif op == "temp":
                twin.set_sensors(temperature=int(arg))
            elif op == "weight":
                twin.set_sensors(weight=int(arg))
            elif op == "silent":
                twin.set_faults(disconnected=arg == "1")
            elif op == "corrupt":
                twin.set_faults(corrupt_next=int(arg))
            elif op == "state":
                return summary()
            elif op == "quit":
                raise SystemExit(0)
            else:
                return "commande inconnue"
            return "ok"
        except (ValueError, TypeError) as exc:
            return f"erreur : {exc}"

    last = time.monotonic()
    last_print = 0.0
    stdin_ok = sys.stdin.isatty() and os.name != "nt"  # sous Windows : utiliser --send
    while True:
        now = time.monotonic()
        twin.advance(min(now - last, 1.0))
        last = now
        conn.poll()
        try:
            c, _ = ctl.accept()
            c.setblocking(False)
            clients.append([c, b""])
        except BlockingIOError:
            pass
        for entry in list(clients):
            c, buf = entry
            try:
                data = c.recv(4096)
            except BlockingIOError:
                continue
            except OSError:
                data = b""
            if not data:
                clients.remove(entry)
                c.close()
                continue
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                reply = command(line.decode("utf-8", "replace"))
                try:
                    c.sendall((reply + "\n").encode())
                except OSError:
                    pass
            entry[1] = buf
        if stdin_ok and select.select([sys.stdin], [], [], 0)[0]:
            print(command(sys.stdin.readline()), flush=True)
        if not args.quiet and now - last_print > 5:
            last_print = now
            print(summary(), flush=True)
        time.sleep(0.01)


if __name__ == "__main__":
    main()
