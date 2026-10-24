# Sichtbare Fernbedienung und SmartTV

## Einrichtung

```text
/boiler display source <tvDisplayId> smarttv
/boiler display source <remoteDisplayId> remote <tvDisplayId>
```

TV und Fernbedienung sind zwei verschiedene Displays. Die Fernbedienung speichert ihre Ziel-ID als `link` in den Quelldaten. Auch mehrere Fernbedienungen können denselben TV bedienen. Fehlende oder noch ladende Ziele werden als „nicht bereit“ angezeigt und automatisch erneut aufgelöst. Selbstverknüpfungen werden abgewiesen. Eine Fernbedienung ersetzt niemals die Quelle ihres Ziels.

Ab 128×384 Pixeln (1×3 Karten) enthält das Hochformat alle Tasten. Breite Displays ab 384×128 Pixeln ordnen die Gruppen nebeneinander an. Kleinere Displays bieten die Seiten **TV**, **Medien**, **123** und **Text**. Darstellung und Klickflächen verwenden dieselbe skalierte Geometrie. Ein Klick hebt die Taste kurz hervor. Nicht unterstützte Aktionen sind ausgegraut; ein Klick erklärt die Einschränkung. Fähigkeiten werden regelmäßig und vor jedem Klick aktualisiert.

| Berechtigung | Zweck |
| --- | --- |
| `boiler.command.action` | Fernbedienung, normale App-Eingaben und Lautstärke bedienen |
| `boiler.command.source` | Quellen einrichten, wechseln und nach Stopp erneut starten |
| `boiler.command.settings` | Einstellungs-App öffnen und Displayoptionen ändern |

Der frühere Befehl `/boiler remote` und seine Item-/Bewegungslistener sind entfernt. Bereits ausgegebene Items bleiben normale Gegenstände und sperren keine Spielersteuerung mehr. Die Tastatur-Quelle bleibt unverändert verfügbar; Fernbedienung und SmartTV benötigen keine Client-Mod. Der optionale BoilerClient wird über `boiler.stream` konfiguriert.

## Bedienung und getrennte Eingaben

| Taste | SmartTV-Oberfläche | Aktive App |
| --- | --- | --- |
| Pfeile | Navigation | `ARROW_UP/LEFT/RIGHT/DOWN` |
| OK | Auswahl bestätigen | `ENTER` |
| Home | Vollständige Startseite öffnen | App beenden, Startseite öffnen |
| Zurück | Eingabe/Unteransicht schließen | `ESCAPE` |
| Einstellungen | Einstellungen öffnen/schließen | Einstellungen als Overlay öffnen/schließen; App läuft weiter |
| 0–9 | Normale Tasteneingabe | Normale Tasteneingabe |
| Esc, Tab, Back, Entf, Pg↑/Pg↓ | Normale Tasteneingabe | `ESCAPE`, `TAB`, `BACKSPACE`, `DELETE`, `PAGE_UP/DOWN` |
| Text | Nicht verfügbar | Privater Eingabedialog für die aktuelle App |

Home ist eine eigene `RemoteAction.HOME` und wird **niemals** als Tastendruck an die App gesendet. Auch während des Ladens, nach Fehlern, beim Pausieren und nach Stopp bleibt Home erreichbar. Normale `SHIFT`-, `ESCAPE`- und Tastatur-`HOME`-Ereignisse werden während der App unverändert weitergereicht. Bei einem Ziel ohne SmartTV ist Home nicht verfügbar. Andere Spieler können eine laufende private Eingabe weder übernehmen noch mit Home abbrechen.

**Stopp** beendet die untergeordnete Sitzung und zeigt „Gestoppt“. **Wiedergabe** startet die zuletzt gewählte Quelle mit ihren Parametern neu. Die bisherige Wiedergabeposition wird dabei nicht wiederhergestellt. Home zeigt dagegen die Startseite und behält Parameter und Verlauf.

**Text** zeigt einen privaten Minecraft-Dialog mit Textfeld, **Uebernehmen** und **Abbrechen**. Escape fuehrt ebenfalls die Abbrechen-Aktion aus. Die Antwort wird nur an die aktuelle App und nur vom ausloesenden Spieler angenommen. Eigenes Home, App-Wechsel, Sitzungsende oder 120 Sekunden ohne Eingabe schliessen den Dialog und geben die TV-Eingabe frei. Der normale Chat wird nicht abgefangen. `/boiler display action <id> input <text>` bleibt ebenfalls verwendbar.

Die Browser-Kachel startet die registrierte Quelle `browser` direkt mit `https://www.google.com/`. Die URL wird auch bei gespeicherten alten Werten und beim erneuten Einrichten nicht abgefragt. Die Browser-Quelle muss wie bisher im Server registriert sein.

Die Dialoge nutzen das native Minecraft-1.21.6-Format ueber `minecraft:dialog show/clear`. Eine neuere Paper-API ist daher nicht erforderlich. Dialog-Buttons senden ihre Antworten ueber einen spielergebundenen, zufaelligen Einmalschluessel; alte Antworten koennen keinen spaeteren Dialog oder eine andere App bedienen. Die Konsole oeffnet den Dialog; Spieler benoetigen dafuer keine Operatorrechte. Die eigentliche Bedienung prueft weiterhin die vorhandenen Boiler-Berechtigungen.

MapEngine-Klicks können auf Netty-Threads eintreffen. Boiler übernimmt Klicks, Scrollen, Text, Tasten und TV-Aktionen vor der Quellenverarbeitung auf den Serverthread. Dadurch werden auch Dialoge dort geöffnet und geschlossen. Wartende Eingaben an bereits ersetzte Quellen oder entfernte Displays werden verworfen.

Die Dialogbefehle wählen den Spieler ausdrücklich mit `@a[name="Spielername",limit=1]` aus. Eine nackte UUID wird von neueren Versionen bei Spielerargumenten als mögliches Entity-Ziel abgewiesen. Fehlgeschlagenes Öffnen gibt die Eingabe frei; auch ein Fehler beim Schließen verhindert die Freigabe nicht und verdeckt keinen vorherigen Fehler.

## Medien und Ton

FFmpeg-basierte Quellen, VLC und GStreamer melden ihre Fähigkeiten an SmartTV. Pause/Fortsetzen erhält die Position. Die Spultasten springen zehn Sekunden; Suchfähigkeit wird erst nach einer positiven Backend-Prüfung freigegeben. Bei als `live` markierten Streams sind Pause und Spulen deaktiviert. Im Zweifel bleibt Spulen gesperrt. Quellenwechsel und Ladezustände aktualisieren die verfügbaren Aktionen.

FFmpeg führt Steueraufträge auf seinem Decoderthread aus. Ein Sprung verwirft alte Bild-/Audioereignisse und beginnt eine neue Zeitbasis; auch bereits blockierte Schreibaufträge können keine alten Daten nachliefern. Pausierte Warteschlangen bleiben begrenzt. VLC-/GStreamer-Abfragen, Transportaufträge und die native Freigabe werden serialisiert außerhalb des Serverthreads ausgeführt.

Lautstärke wird vor der Sprachchat-Kodierung zentral auf serverseitiges PCM16-Audio angewandt. Schritte: 5 Prozentpunkte, Grenzen: 0–100 %. Stumm ist unabhängig von der Lautstärke gespeichert. Beide Werte liegen im bestehenden `settings`-Objekt (`volume`, `muted`), gelten auch nach Neustart und erscheinen unter **Ton**. Die ursprünglichen PCM-Daten werden nicht verändert. Normale Lautstärketasten benötigen keine Einstellungsberechtigung.

Die verfügbaren Medienaktionen richten sich nach der aktiven Quelle; Erweiterungen müssen zusätzliche TV-Aktionen ausdrücklich anbieten.

## Startseite und Einstellungen

Ab **384×256 Pixeln (3×2 Karten)** beziehungsweise **256×384 Pixeln (2×3 Karten)** erscheint die webOS-inspirierte Startseite mit dunklem Hintergrund, Banner, farbigen Kategorien, App-Leiste, Seitenleiste und zuletzt verwendeten Quellen. Kleinere Displays verwenden kompakte Kacheln. Grafik und Icons werden lokal gezeichnet und unveränderte Frames zwischengespeichert.

SmartTV, Einstellungen und Fernbedienung verwenden die originale Minecraft-Bitmap-Schrift aus der Server-API (`MinecraftFont.Font`). Die Glyphen werden ohne Betriebssystemschrift direkt als Pixelblöcke gezeichnet, ausschließlich in ganzzahligen Stufen (1×, 2×, 3× usw.). Breitenmessung, Zeilenumbruch und Kürzung verwenden dieselben Glyphen. Umlaute und ß stammen ebenfalls aus der Minecraft-Schrift; fehlende Zeichen erhalten ein Minecraft-Ersatzzeichen. Symbole werden auf einem Pixelraster gerendert und mit Nearest-Neighbor ganzzahlig vergrößert. Es gibt keine Kantenglättung oder nachträgliche Skalierung des UI-Frames. Auf kleineren TVs enthält die App-Leiste weniger Einträge pro Seite, damit Namen lesbar bleiben. Kleine Einstellungsansichten ordnen Beschriftung und Wert untereinander an.

Links/rechts wechselt zwischen Apps; hoch führt zu Kategorien und Seitenleiste, runter zu zuletzt verwendeten Quellen. OK bestätigt. Kategorien, Apps, Seitenpfeile, Quellenkarten und Seitenleiste sind auch direkt anklickbar. WASD und Space/Enter der bestehenden Tastatur-Quelle bleiben zur Navigation nutzbar.

Die Einstellungs-App benötigt `boiler.command.settings` und die Bedienberechtigung, aber keine Quellenberechtigung.

Während einer App werden die Einstellungen als Overlay über deren laufendem Bild gezeichnet. Die Quelleninstanz, Wiedergabeposition und Audioausgabe bleiben erhalten. Pfeile, OK, Klicks und Scrollen steuern das Overlay; normale App-Texteingaben und Cursorbewegungen werden dabei nicht an die App weitergereicht. Zurück, Escape oder ein erneuter Druck auf Einstellungen schließen das Overlay und geben die Eingabe an die unverändert laufende Quelle zurück. Home beendet weiterhin die App. Eine eigene offene Eingabe wird beim Umschalten abgebrochen; die private Eingabe eines anderen Spielers bleibt geschützt.

Bei Clone wird die gemeinsame Map-ID-Verbindung vorübergehend gelöst, das Ziel weiter als Hintergrund gezeichnet und die Verbindung beim Schließen wiederhergestellt. Die Clone-Quelle wird dabei nicht entladen. Beim BoilerClient wird der serverseitig gerenderte Bildinhalt einschließlich der Einstellungsansicht übertragen.

| Bereich | Optionen |
| --- | --- |
| Bild | Farbdithering, leuchtende Rahmen, Kartendrehung, Blickrichtung, horizontaler/vertikaler Drehwinkel |
| Leistung | Bildpuffer, Paketbündelung, Renderintervall, Sichtweite |
| Ton | Tonreichweite, Lautstärke, Stumm |

Hoch/runter wählt Optionen, links/rechts ändert Werte. OK schaltet Schalter um oder öffnet bei Zahlen eine private Eingabe. Klicks auf Werte/Pfeile funktionieren ebenfalls; Rechtsklick setzt den Standard zurück. Zurück schließt zuerst eine eigene Eingabe, dann die Einstellungen. Änderungen werden sofort gespeichert; Tonreichweite gilt beim nächsten Start einer Audioquelle.

## Quellen, Parameter und Lebenszyklus

Im SmartTV werden ausschließlich die registrierten Quellen `yt-tv`, `browser` und `mediaplayer` alphabetisch angeboten, zusätzlich zur Einstellungs-App. Diese Beschränkung gilt auch für Kategorien und zuletzt verwendete Quellen. Andere Quellen bleiben über die normalen Befehle nutzbar; gespeicherte Parameter bleiben erhalten. Fehlende Parameter werden aus `@CreateCommandArguments` übernommen und einzeln in privaten Minecraft-Dialogen erfragt. Ganze Zahlen, endliche Dezimalzahlen sowie `true`/`false` werden validiert; ungültige Eingaben werden erneut abgefragt. `abbrechen`, Zurück, eigenes Home oder 120 Sekunden ohne Eingabe beenden die Abfrage.

Vollständige gespeicherte Parameter werden beim nächsten Start wiederverwendet. **Rechtsklick auf eine App-Kachel** fragt die Parameter neu ab; ein Punkt übernimmt den bisherigen Feldwert. Ein Spieler kann gleichzeitig nur eine Abfrage besitzen, und währenddessen besitzt er die gemeinsame TV-Eingabe exklusiv.

SmartTV bleibt die äußere Displayquelle. Seine Daten enthalten `selected`, `sources` und `recent` (bis zu sechs Quellen). Displayoptionen bleiben im bisherigen `settings`-Objekt. Nach Serverstart erscheint zunächst das Menü. Ein Display-Adapter hält interne Quellenwechsel, Fehlerquellen und `saveSourceData` innerhalb der untergeordneten Sitzung. Veraltete Ladeergebnisse/Callbacks dürfen nach Home oder App-Wechsel nichts zurückholen. Beim Beenden werden Clone-Verbindungen, Renderpause, Viewport und Client-Darstellungsmodus zurückgesetzt.

Die öffentliche API ergänzt `RemoteAction` und die optionalen Default-Methoden `remoteActions()` und `onRemoteAction(...)` auf Quelle und Display. Abfragen müssen nicht blockierend sein und nur tatsächlich verfügbare Aktionen melden. `onKey`/`onInput` bleiben unverändert; bestehende Quellen müssen nicht angepasst werden.

## Automatisierte Prüfung

Zusaetzliche Dialogtests pruefen Spielerbindung, Escape/Abbrechen, Timeout, Einmalantworten, veraltete Antworten, Makro-Escaping und den Brigadier-Befehlspfad. Browser-Starts pruefen den Google-Standard ohne URL-Dialog. Die Tests umfassen Layouts und Klickflächen für 128×128, 128×256, 128×384, 384×128 und größere Displays, Zielauflösung und Wiederverbindung, Fähigkeiten und Berechtigungen, getrennte App-/Home-Eingaben, Home während verspäteten Ladens und nach Fehlern, genau einmaliges Entladen, Stopp/Neustart sowie private App-Texteingabe per Dialog. Native FFmpeg-Tests prüfen Pause/Fortsetzen, Positionswechsel, Live-Einschränkungen, Audioformat und Thread-Freigabe. Weitere Tests prüfen serialisierte native Steueraufträge, PCM-Lautstärke ohne Datenmutation und das Verwerfen alter Puffer nach Sprüngen. Bestehende Lifecycle-, Rendering-, Clone- und Persistenztests laufen weiter.

```powershell
$env:JAVA_HOME = 'C:\Pfad\zum\jdk-21'
.\gradlew.bat --no-daemon --console=plain --gradle-user-home build/gradle-user-home build
```

JAR: `build/libs/Boiler-3.0.3-all.jar`. Testbericht: `plugin/build/reports/tests/test/index.html`. Render-Vorschauen: `plugin/build/reports/remote-preview/` und `plugin/build/reports/smarttv-preview/`.

## Verbleibende Live-Abnahme

Im Workspace steht kein laufender Paper-Server mit Spielern, Sprachchat und Streaming-Clients zur Verfügung. VLC/GStreamer werden über die gemeinsame Steuerauftragsverwaltung automatisiert geprüft; ihre echten installierten nativen Backends benötigen zusätzlich die folgende Live-Prüfung auf Paper 1.21.6 mit MapEngine und MapMediaExt:

1. TV und Remote als getrennte Displays erstellen. Alle Layoutgrößen, Seiten, Klickmarkierungen und ausgegraute Tasten prüfen. Zwei Fernbedienungen mit demselben TV verbinden. Ziel entfernen/neu laden, unterschiedliche Neustartreihenfolgen und Selbstverknüpfung prüfen.
2. Bild, Mediaplayer, YouTube/Twitch und Clone mit Karten und BoilerClient öffnen. Home aus Wiedergabe, Pause, Stopp, Lade- und Fehlerzustand betätigen. Späte Ergebnisse dürfen die Startseite nicht ersetzen. Bild-/Audioausgabe, Clone-Verbindung und Client-Modus müssen korrekt enden.
3. Mit lokalem Video samt Ton Pause/Fortsetzen und beide Spulrichtungen prüfen: Position erhalten, neue Bild-/Tonposition synchron, keine alten Tonreste. Live-Streams dürfen Pause/Spulen nicht anbieten.
4. Shift, Escape und Tastatur-Home in einer App prüfen. Zurück muss dort Escape senden; ausschließlich der TV-Home-Button beendet die App. Stopp und erneute Wiedergabe mit gespeicherten Parametern prüfen.
5. Mit zwei Spielern private Quellen- und Textabfragen, fremdes Home, Timeout, Rechteentzug, Logout, App-Wechsel und Display-Entfernung prüfen. Der Dialog muss geschlossen und die TV-Bedienung wieder frei sein. Der normale Chat bleibt jederzeit nutzbar.
6. Dithering, Leistung, Ausrichtung, Lautstärke und Stumm ändern und nach Neustart vergleichen. Vol± darf unabhängig von Stumm und ohne Einstellungsberechtigung funktionieren. Alte Remote-Items dürfen keinerlei Bewegungssperre mehr auslösen.
