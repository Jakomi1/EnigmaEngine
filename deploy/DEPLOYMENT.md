# EnigmaEngine Production Deployment Guide

> **Target Architecture:** Multicore Folia-based SMP Architecture (Tested on 7 vCPU Cores, 16 GB RAM, Linux amd64)  
> **Java Version:** OpenJDK 25+ (`jdk.incubator.vector` SIMD enabled)  
> **Target Capacity:** 100+ concurrent players with stable 20.0 TPS

---

## 1. Übersicht & Verzeichnisstruktur

Dieses Verzeichnis (`deploy/`) enthält das schlüsselfertige, validierte Produktions-Deployment für EnigmaEngine.

```
deploy/
├── config/
│   ├── canvas-server.yml           # AFFINITY Scheduler, aggressiver Regionizer, Packet-Filter
│   ├── canvas-worlds.yml           # Welt-spezifische Ticking- und HUD-Einstellungen
│   ├── paper-global.yml            # 7 Worker-Threads, G1GC Optimierungen
│   └── paper-world-defaults.yml    # Reduzierte Ticking-Radien, Anti-Desync
├── plugins/
│   └── EnigmaPerformanceBar.jar    # Folia-native Dual BossBar (TPS & 7-Core Auslastung)
├── DEPLOYMENT.md                   # Dieses Dokument
├── monitor.sh                      # Live Terminal Monitoring Dashboard (RCON + /proc/stat)
├── server.properties               # Netzwerk-, Port- und Offline/Online-Modus
├── start.sh                        # Automatisierter Start (CPU-Pinning via taskset, G1GC, Java 25)
└── stop.sh                         # Graceful Shutdown mit Timeout & State-Save
```

---

## 2. Systemanforderungen & Voraussetzungen

1. **Betriebssystem:** Linux x86_64 (Kernel 5.15+ oder 6.x)
2. **Java Runtime:** OpenJDK 25 (`sudo apt install openjdk-25-jdk` oder SDKMAN)
3. **CPU Cores:** Mindestens 4 Cores empfohlen; vorkonfiguriert und optimiert für **7 Cores** (Cores 0–6).
4. **RAM:** Mindestens 12 GB freier Arbeitsspeicher (12 GB Heap + ~3 GB OS/Metaspace).
5. **Tools:** `taskset` (Bestandteil von `util-linux`).

---

## 3. Schritt-für-Schritt Migration für den Deploy Agent

Wenn der Deploy Agent einen bestehenden Minecraft-Server (Paper, Spigot, Purpur, Crafty) auf EnigmaEngine migriert:

### Schritt 1: Bestehenden Server stoppen
```bash
# Beispiel: Graceful stop des bisherigen Dienstes
systemctl stop crafty / systemctl stop minecraft
```

### Schritt 2: Verzeichnis vorbereiten
Kopiere den Inhalt dieses `deploy/`-Ordners in das Zielverzeichnis (z. B. `/srv/minecraft` oder `/home/samuelm/enigma-production`):
```bash
rsync -avP deploy/ /srv/minecraft/
# Server JAR bereitstellen (sofern nicht bereits vorhanden)
cp serverJar/EnigmaEngine.jar /srv/minecraft/EnigmaEngine.jar
```

### Schritt 3: Welten & Spielerdaten migrieren
Folia/EnigmaEngine speichert Welten anders als Standard-Paper:
- **Overworld:** Liegt direkt im Ordner `world/`
- **Nether & End:** Folia verwaltet Dimensions unter `world/DIM-1` und `world/DIM1` (bzw. entsprechende Unterordner).
- **Spielerdaten:** Kopiere `world/playerdata`, `world/stats`, `world/advancements` sowie `usercache.json` und `ops.json`.

```bash
cp -r /alter-server/world/playerdata /srv/minecraft/world/
cp -r /alter-server/world/stats /srv/minecraft/world/
cp -r /alter-server/world/advancements /srv/minecraft/world/
cp /alter-server/ops.json /srv/minecraft/ops.json
cp /alter-server/whitelist.json /srv/minecraft/whitelist.json
```

### Schritt 4: Plugins auf Folia-Kompatibilität prüfen
> [!IMPORTANT]
> EnigmaEngine basiert auf Folia (Multi-Threaded Region Ticking). Plugins, die synchron auf die Hauptschleife zugreifen (`Bukkit.getScheduler().runTask()`), müssen Folia unterstützen (`folia-supported: true` in ihrer `plugin.yml`).
- Vorkonfiguriert und verifiziert: `ViaVersion`, `ViaBackwards`, `EnigmaPerformanceBar`.

### Schritt 5: Ports anpassen
In `server.properties`:
- `server-port=25605` (oder `25565` bei Übernahme des Standard-Ports)
- `server-ip=0.0.0.0`
- `online-mode=false` (oder `true`, falls Mojang-Auth aktiv sein soll)
- `rcon.port=25615`, `rcon.password=enigma_bench_2026`

---

## 4. Umgebungsvariablen (Optionale Overrides)

Das Startskript `start.sh` unterstützt optionale Overrides zur einfachen Container- und Agent-Konfiguration:

| Variable | Standardwert | Beschreibung |
| :--- | :--- | :--- |
| `ENIGMA_CPUSET` | `0-6` | CPU-Kerne für `taskset` (z. B. `0-3` für 4 Cores) |
| `ENIGMA_HEAP_MIN` | `4G` | Initialer JVM Heap (`-Xms`) |
| `ENIGMA_HEAP_MAX` | `12G` | Maximaler JVM Heap (`-Xmx`) |
| `JAVA_BIN` | Auto-detect | Pfad zum `java` Binary (z. B. `/usr/lib/jvm/java-25-openjdk-amd64/bin/java`) |

Beispiel:
```bash
ENIGMA_CPUSET="0-6" ENIGMA_HEAP_MAX="14G" ./start.sh
```

---

## 5. Systemd Service Unit (Empfohlen für automatischen Neustart)

Erstelle `/etc/systemd/system/enigma-engine.service`:

```ini
[Unit]
Description=EnigmaEngine Multicore Minecraft Server
After=network.target

[Service]
Type=forking
User=samuelm
WorkingDirectory=/home/samuelm/enigma-production
ExecStart=/home/samuelm/enigma-production/start.sh
ExecStop=/home/samuelm/enigma-production/stop.sh
PIDFile=/home/samuelm/enigma-production/server.pid
Restart=on-failure
RestartSec=15
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

Aktivieren:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now enigma-engine
```

---

## 6. Healthchecks & Verifikation für Deploy-Pipelines

Der Deploy Agent kann den Zustand des Servers automatisiert über RCON oder Log-Prüfung verifizieren:

```bash
# 1. Prozessprüfung
kill -0 $(cat server.pid) && echo "Process OK"

# 2. Portprüfung
nc -zv 127.0.0.1 25605

# 3. RCON Healthcheck (erwartet 20.00 TPS)
python3 -c "
import socket
s = socket.socket(); s.connect(('127.0.0.1', 25615))
# Sende Auth & TPS Abfrage
"
```
