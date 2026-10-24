# DJ Controller

```text
/boiler display source <displayId> djcontroller
```

Die Source ist auch im SmartTV als **DJ Controller** unter **Werkzeuge** verfügbar. Zwei Maps nebeneinander (256×128 Pixel) zeigen Deck A, Mixer und Deck B. Größere Displays skalieren das Layout proportional mit schwarzen Rändern.

## Tracks laden

Audiodateien liegen wie beim Mediaplayer in `plugins/Boiler/media`. **LOAD** öffnet einen Dateibrowser als Overlay direkt über dem abgedunkelten Controller. Der Titel zeigt das gewählte Deck A oder B. Ordner und Audiodateien lassen sich direkt anklicken; Enter oder **OEFFNEN** öffnet den ausgewählten Eintrag. Scrollen/W/S navigiert, die unteren Pfeile blättern, **<** geht zum übergeordneten Ordner und **R** aktualisiert. **X** oder Escape schließt das Overlay. Nach der Dateiauswahl schließt es automatisch. Währenddessen spielen beide Decks weiter; Bedienelemente hinter dem Overlay sind gesperrt.

Es gibt keine Pfadeingabe und keinen Minecraft-Dialog. Nach außen führende Links und Nicht-Audiodateien sind nicht auswählbar. Unterstützte Endungen entsprechen dem Mediaplayer; der tatsächliche Codec muss vom gebündelten FFmpeg decodierbar sein.

Ein Track wird vollständig vorbereitet und anschließend pausiert geladen. Die Vorbereitung zeigt einen Fortschritt und verwendet einen temporären PCM-Cache auf Platte (48 kHz, Mono, 16 Bit; ungefähr 5,8 MB pro Minute). Beim Ersetzen läuft der bisherige Track bis zum erfolgreichen Laden weiter. Ein Fehler betrifft nur das gewählte Deck.

## Bedienung

Auf das gewünschte Rädchen oder den Fader schauen und mit dem Mausrad scrollen. Rechtsklick auf Regler setzt den Standardwert zurück.

| Element | Wirkung |
| --- | --- |
| `>/II` | Deck starten/pausieren; am Ende startet es erneut |
| CUE | Pausieren und zum Anfang springen |
| Turntable | 250 ms pro Scrollschritt spulen; Rechtsklick wie CUE |
| Tempo-Fader neben dem Turntable | −16 bis +16 %, Schritte von 0,5 %, Tonhöhe bleibt erhalten |
| HI / MID / LO | Höhen, Mitten, Tiefen pro Deck; −24 bis +12 dB, Schritte von 1 dB |
| Kanal-Fader neben dem EQ | 0–100 %, Schritte von 5 % |
| M | Masterpegel 0–100 %, Schritte von 5 % |
| Crossfader unter dem Mixer | Links Deck A, rechts Deck B; Mitte mischt beide mit Equal-Power-Kurve |
| IN | Loop-Anfang auf die nächstgelegene Beatlinie setzen |
| OUT | Loop-Ende auf die nächstgelegene Beatlinie setzen und Loop starten; mindestens einen Beat nach IN |
| LOOP | Den gesetzten Loop aus-/einschalten |
| LEAD | Referenzdeck als Toggle wählen; es kann nur ein LEAD geben. Erneutes Klicken hebt die Auswahl und die Nachführung auf |
| SYNC | Dauerhafte Tempo- und Beatgrid-Nachführung am anderen Deck ein-/ausschalten; zuerst LEAD wählen |

IN und OUT rasten auf der jeweils nächstgelegenen gültigen Beatlinie innerhalb des Tracks ein. Liegen beide auf derselben Linie, bleibt OUT ohne Wirkung und zeigt einen Hinweis. Ohne erkannten Beat werden die Positionen frei gesetzt; dann beträgt die Mindestlänge 100 ms. Spulen außerhalb des aktiven Loops deaktiviert ihn. Loop-Zeiten beziehen sich auf die ursprüngliche Trackposition; Tempo verändert die hörbare Dauer. Kurze Blenden reduzieren Knackser an Sprüngen. Der EQ arbeitet mit Trennfrequenzen von 250 Hz und 4 kHz. Bei neutralen EQ-Werten bleibt das Signal unverändert. Alle Regleränderungen werden geglättet; die finale PCM-Ausgabe wird gegen Übersteuerung begrenzt.

Die Pegelanzeige neben jedem Turntable zeigt das Decksignal einschließlich Kanalpegel vor dem Crossfader. Die bestehende Displaylautstärke und Stummschaltung wirken zusätzlich auf den Gesamtmix. Für Ton werden der vorhandene Sprachchat-Ausgabepfad und dessen Voraussetzungen benötigt.

Oben liegen beide Waveforms übereinander über die gesamte Controllerbreite, Deck A mit türkiser und Deck B mit oranger Randmarkierung. Beide zeigen einen laufenden Drei-Sekunden-Ausschnitt um dieselbe mittige weiße Abspielmarke. Titel und effektive BPM stehen darüber; ohne Erkennung erscheint `--.- BPM`. Bassanteile erscheinen rot, Mitten grün und Höhen blau; Mischfarben ergeben sich aus der tatsächlichen Energie der drei Frequenzbänder. Die Anzeige folgt Pause, Tempo, Jogging, Cue, SYNC und Loops. Die Wellenform stammt aus dem Originalaudio vor EQ und Lautstärke und wird beim Vorbereiten aus PCM-Minima, -Maxima und Bandenergien berechnet. Die Daten bleiben auf höchstens 32768 Zeitabschnitte pro Track begrenzt; beim Zeichnen wird keine Datei gelesen. Unten befinden sich pro Deck getrennte LEAD- und SYNC-Toggles; aktive Toggles sind hervorgehoben.

SYNC verwendet eine beim Laden geschätzte BPM-Zahl und Beatphase. Es analysiert bis zu zwei Minuten Audio im Bereich 80–180 BPM und benötigt mindestens vier Sekunden. Die Tempoanalyse wertet das Spektrum der rhythmischen Einsätze einschließlich ihrer Oberwellen aus; die Beatphase wird aus einem geglätteten Phasenhistogramm bestimmt, damit Hi-Hats und andere Zwischenschläge gültige Beats nicht gegenseitig auslöschen. Stille und Dauertöne liefern keine BPM. Das SYNC-Deck übernimmt das effektive Tempo des gewählten LEAD-Decks und springt beim Einschalten zum nächstgelegenen passenden Beat. Solange SYNC aktiv ist, werden Tempoänderungen des LEAD-Decks automatisch übernommen; wenn beide Decks spielen oder beide pausieren, werden Positionsänderungen wieder am Beatgrid ausgerichtet. Play/Pause bleibt unabhängig bedienbar; beim gemeinsamen Weiterspielen wird die Phase erneut angeglichen. Play/Pause und das Referenzdeck bleiben unverändert. Ein aktiver Beat-Loop bleibt erhalten; die synchronisierte Position wird bei Bedarf phasengleich in den Loop zurückgeführt. Bei fehlenden Tracks, nicht eindeutig erkanntem Beat oder nötigem Tempo außerhalb von ±16 % erfolgt keine Änderung; ein Hinweis erscheint im Status. Erneutes SYNC schaltet die Nachführung aus und behält das zuletzt angeglichene Tempo bei. LEAD-Wechsel beendet SYNC am neuen Referenzdeck; am anderen Deck lässt sich SYNC anschließend einschalten. Laden eines neuen Tracks beendet die betroffene Nachführung. Wird der nötige Tempobereich überschritten, schaltet sich SYNC mit einem Hinweis aus. Bei Musik mit wechselndem Tempo kann das geschätzte feste Beat-Raster abweichen.

Bedienung erfordert `boiler.command.action`, Laden zusätzlich `boiler.command.source`. Die beiden Decks und der Dateibrowser gehören zum Display und werden gemeinsam bedient. Nur die zuletzt besuchten Ordner werden gespeichert. Nach Neustart sind die Decks leer, EQ neutral, Tempo 0 %, Kanal- und Masterpegel 100 %, Crossfader mittig und Loops aus.

Quellenwechsel und Plugin-Abschaltung beenden die Engine, brechen Vorbereitungen ab und entfernen temporäre PCM-Dateien. Scratching ist nicht enthalten.

Das erkannte Beatgrid erscheint als senkrechte Linien über der Waveform. Der Ausschnitt umfasst drei Sekunden Wiedergabezeit und berücksichtigt das eingestellte Tempo. SYNC gleicht dadurch auch Abstand und Phase der sichtbaren Beatlinien zwischen beiden Decks an. Anzeige, Loop-Einrastung und SYNC verwenden dasselbe Track-Raster. Ohne erkannten Beat erscheinen keine Rasterlinien.

Der gesetzte Loop-Bereich wird in der jeweiligen Waveform hinterlegt und an IN/OUT mit nach innen gerichteten Markierungen begrenzt. Bei aktivem Loop leuchtet die Umrandung in der Deckfarbe, bei deaktiviertem Loop bleibt sie abgedunkelt sichtbar. Schon ein einzelner IN-Punkt wird angezeigt. Außerhalb des sichtbaren Ausschnitts liegende Bereiche werden abgeschnitten; die Markierung folgt Trackposition und Tempo.

Ein abgelehnter SYNC-Versuch zeigt einen roten `SYNC!`-Button und einen roten Statushinweis mit dem Grund, etwa `SYNC: >16%`.

## Prüfung

`DJControllerSourceTest` prüft Dateiladen über das Overlay, skalierte Klickflächen, gesperrte Hintergrundbedienung, Rechte, fehlerhafte Dateien, unabhängige Decks, Scroll-Ziele und Unload. `DjEngineTest` prüft native Tempoerhaltung mit Sinussignalen, Dauer, Transport, Loops, EQ, Crossfade und Trefferflächen. Die Layouttests erzeugen Controller- und Overlay-Vorschauen unter `plugin/build/reports/djcontroller-preview`.

Die Live-Abnahme auf Paper mit Sprachchat bleibt separat: zwei echte Tracks mischen, Tonhöhe bei Tempoänderungen vergleichen, Loops hören, alle Scrollflächen auf zwei Maps bedienen und die Source während Vorbereitung bzw. Wiedergabe wechseln.

Optional prüft `DjMusicAnalysisTest` vorhandene lokale MP3s, wenn `BOILER_DJ_TEST_MEDIA` auf den Musikordner gesetzt ist. Es werden keine Musikdateien ins Repository kopiert oder heruntergeladen. Der Bericht mit geschätzten BPM und Beatoffsets liegt unter `plugin/build/reports/dj-music/results.txt`. Die zwölf bereitgestellten Testserver-Songs werden erkannt; zusätzlich prüft ein lokaler Test den vollständigen Lade- und Sync-Pfad mit „Die Welt Brennt“ und „Untergang“.
