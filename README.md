# MC Server – Minecraft-Server auf dem Handy

Android-App (Java), die lokal einen Minecraft-Java-Server startet. Beim ersten Start führt ein
Setup durch Software (Vanilla / Paper), Version, Spiel-Einstellungen, RAM und Port.
Design: helles Design mit orangem Akzent, Terminal-Konsole als Gestaltungselement.

## APK bauen
1. Inhalt des ZIP in ein GitHub-Repo entpacken und pushen.
2. Tab **Actions → Build APK → Run workflow** (nur manueller Start).
3. Fertige APK im Lauf unter **Artifacts → MC-Server-apk**.

## Java-Laufzeit
Die App versucht zuerst eine Android-kompatible Java-21-Laufzeit (ARM64) zu installieren und direkt zu starten.
Das umgeht auf manchen Android-Geräten den HotSpot-Fehler `Failed to mark memory page as executable`,
der beim Ausführen von Alpine-OpenJDK unter proot durch Androids Speicher-/Execmem-Schutz entstehen kann.
Falls diese Laufzeit nicht geladen oder gestartet werden kann, versucht die App weiterhin den bisherigen
Alpine-/proot-Weg als Rückfall. Dieser Rückfall kann auf betroffenen Geräten weiterhin fehlschlagen.

## Schriftart
Die Claude-Schrift ist proprietär und deshalb nicht enthalten. Standard ist die System-Serifenschrift.
Eigene Schrift: Datei als `app/src/main/res/font/claude_font.ttf` ablegen – sie wird automatisch verwendet.

## Hinweise
- `targetSdk` ist bewusst 28, weil Android ab 29 das Ausführen von Programmen aus dem App-Speicher blockiert.
- Server-Daten liegen im App-Speicher (`files/server`).
- Server läuft als Vordergrunddienst; Akku-Optimierung für die App ausschalten.
