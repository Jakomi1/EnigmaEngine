# EnigmaEngine 100-Player Benchmark & Multicore Analysis

## Executive Summary

- **Testumgebung:** 7 vCPU Cores (QEMU Virtual CPU v2.5+ @ 2.4 GHz), 16 GB RAM, 4 GB Swap, OpenJDK 25.
- **Szenario:** 100 gleichzeitige Netzwerk-Protokoll-Spieler (40 Spieler dicht gedrängt im Spawn-Cluster, 60 Spieler aufgeteilt auf 6 Außenregionen).
- **Vor Optimierung:** 50 Spieler führten zu starken Drops auf ~12.0 TPS.
- **Nach EnigmaEngine Multi-Core Tuning:** Stabile **20.00 TPS** (14.8 ms MSPT, >70% Leistungsreserve), CPU-Last gleichmäßig über alle 7 Cores verteilt (~45.5% pro Core).

---

## 1. Architektur-Vergleich: Native Folia AFFINITY vs. Distributed Cluster

| Kriterium | Native Folia Multi-Core (Empfohlen) | Distributed Cluster (WORLD_HOST + COMPUTE_HOST) |
| :--- | :--- | :--- |
| **Laufzeit** | 1 JVM, geteilter RAM, Folia Region-Threads | 2 JVMs, TCP-Loopback via Netty |
| **RAM Overhead** | 12 GB Heap in 1 JVM, minimaler Metaspace | Gedoppelter Metaspace, GC & JIT Threads |
| **Latenz** | < 0.01 ms (Shared Memory Heap) | 1.5–5 ms (Netty TCP Serialisierung von Regionen) |
| **Stabilität** | **Produktionsreif & Stabil** | **Experimentell** (Ledger-Desync bei Live-Migration) |
| **Fazit für 7-Core VPS** | **Optimale Wahl** | Nicht empfohlen auf Einzel-Hosts |

---

## 2. Aufgedeckte Bugs & Root-Cause Analyse

1. **Leaf Fluid-Spread Bug (`FlowingFluid.java.patch`):**
   - Leaf (Paper-Fork) optimiert fließendes Wasser mit BFS-Warteschlangen ohne Folia-Regionsbesitzprüfung.
   - Breitet sich Wasser über Regionsgrenzen aus, ruft der Thread `Level.setBlock` in fremden Regionen auf -> `IllegalStateException: Thread failed main thread check: Adding block without owning region`.
   - **Workaround:** Pufferung über `instant-unload-idle-radius: 10` und `fluid-post-processing-algorithm: "FILTERED"`.
2. **Folia AFFINITY Core-Pinning Strictness:**
   - `IllegalArgumentException: Affinity setting needs 1 allocated core per tick thread` falls Thread-Anzahl nicht 1:1 den konfigurierten Cores entspricht.
3. **Task Queue Asynchronous Teleport Race:**
   - Schnelle Massen-Teleports über Regionsgrenzen können bei ungeladenen Chunks Null-Ref-Exceptions in `RegionizedTaskQueue` werfen.

---

## 3. Deployment-Dateien & Verwendung

Alle Produktions-Dateien liegen unter `deploy/`:
- `deploy/start.sh`: Startet den Server isoliert auf Cores 0–6 mit optimalem Heap und G1GC.
- `deploy/stop.sh`: Graceful Shutdown mit World-Saves.
- `deploy/monitor.sh`: Terminal-Dashboard für TPS, CPU & Memory.
- `deploy/plugins/EnigmaPerformanceBar.jar`: Dual In-Game BossBar (`/perfbar`).
- `deploy/DEPLOYMENT.md`: Vollständiger Migrationsleitfaden für automatische Deploy-Agenten.
