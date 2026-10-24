# Einheitlicher Mediaplayer

Die Quelle `mediaplayer` ist zugleich eine SmartTV-App in **Video & Bild**. Ohne Startparameter öffnet sie den lokalen Dateibrowser. Mit einer URL startet sie direkt die Wiedergabe; YouTube und Twitch lösen weiterhin ihre Medien-URLs auf und übergeben sie an denselben Player.

```text
/boiler display source <displayId> mediaplayer
/boiler display source <displayId> mediaplayer https://example.org/video.mp4
/boiler display source <displayId> mediaplayer rtsp://example.org/live
```

Alternativ im SmartTV die App **Mediaplayer** öffnen. Das bisherige SmartTV-Home und dessen Einstellungen funktionieren weiterhin.

## Backend auswählen

```yaml
boiler:
  mediaplayer:
    backend: ffmpeg # ffmpeg, gstreamer oder vlc
```

FFmpeg ist der Standard und verwendet die vorhandenen JavaCV-/FFmpeg-Laufzeitbibliotheken. GStreamer und VLC benötigen zusätzlich eine native Installation in der Architektur der Server-JVM. GStreamer muss seine Bibliotheken und Codec-Plugins finden können; unter Windows werden die vorhandenen `GSTREAMER_1_0_ROOT_*`-Variablen berücksichtigt, alternativ `-Dgstreamer.path=<bin-Verzeichnis>`. VLC benötigt eine auffindbare libVLC-Installation, gegebenenfalls über `-Djna.library.path=<VLC-Verzeichnis>`. Die automatische Suche läuft mit dem Plugin-Classloader, damit vlcj seine Suchmodule auch unter Paper findet. Unter Windows wird dadurch insbesondere `SOFTWARE\VideoLAN\VLC\InstallDir` in der Registry berücksichtigt. Die Installation muss auf dem Rechner liegen, auf dem der Minecraft-Server läuft; eine reine Installation auf einem anderen Client-PC reicht nicht.

Die Auswahl ignoriert Groß-/Kleinschreibung. Fehlende Bibliotheken, ungültige Backendnamen und Decoderfehler erscheinen im Player. Es gibt keinen automatischen Wechsel auf ein anderes Backend. Die Bibliotheken werden erst beim Abspielversuch initialisiert. Nach erneutem Einlesen der Config beziehungsweise Serverneustart gilt die Auswahl für den nächsten Start eines Mediums; laufende Wiedergaben behalten ihr Backend.

Für Aufrufer der Source-API sind `url`, `live`, `loop` und `buffer` verfügbar. `loop` gilt nur für nicht-live Medien. `buffer` steuert die begrenzte FFmpeg-Ereignisqueue (Standard: 10); native Backends verwalten ihre Decoderpuffer selbst. Beliebige native Decoderoptionen und eine Backendwahl pro Quelle werden nicht durchgereicht. RTSP und UDP werden bei direkten URLs standardmäßig als live behandelt; `live` kann explizit gesetzt werden. HTTP-Streams mit Live-Inhalt müssen als `live: true` übergeben werden; Twitch setzt dies bereits.

Die alten Sources `ffmpeg`, `ffmpeg-buffered`, `gstreamer`, `vlc` und `converter` sind entfernt, einschließlich ihrer Befehle. Gespeicherte Displays werden bewusst nicht migriert und müssen auf `mediaplayer` umgestellt werden. Die bisherige Vorabkonvertierung und ihr Cache entfallen.

## Dateien ablegen

Beim Pluginstart wird `plugins/Boiler/media` angelegt. Bilder, Videos, Audiodateien und beliebig verschachtelte Unterordner können dort abgelegt werden:

```text
plugins/Boiler/media/
  Urlaub/
    Strand.png
    Rundgang.mp4
  Musik/
    Lieblingslied.mp3
```

Der Dateibrowser zeigt Ordner zuerst und anschließend Dateien alphabetisch an. IMG, VID und TON kennzeichnen die Medienart. Unbekannte Dateitypen bleiben sichtbar, sind aber nicht abspielbar. Dateien können hinzugefügt oder entfernt werden, während die App geöffnet ist; **R** beziehungsweise der R-Button aktualisiert die Liste. Es gibt keine Lösch-, Upload- oder Schreibfunktion im Browser.

| Medienart | Formate |
| --- | --- |
| Bilder | PNG, JPG/JPEG, GIF, BMP, TIFF/TIF, WBMP |
| Videos | MP4, MKV, WebM, MOV, AVI, M4V, MPEG/MPG, TS/MTS/M2TS, 3GP, OGV, FLV, WMV, VOB |
| Audio | MP3, WAV, FLAC, OGG/OGA, Opus, M4A, AAC, WMA, AIFF/AIF, AC3 |

Bilder verwenden die vorhandene ImageIO-Quelle, Videos und Audio das konfigurierte interne Backend. Ob ein bestimmter Codec innerhalb eines Containers funktioniert, hängt vom gewählten Backend und seinen installierten Codecs ab; beschädigte oder nicht lesbare Dateien erzeugen einen erreichbaren Fehlerzustand. GIF zeigt das erste Bild, keine Animation. Audiodateien zeigen Dateiname und Status; für den serverseitigen Ton gelten weiterhin die Voraussetzungen des vorhandenen Sprachchat-Ausgabepfads.

Die Audioansicht zentriert Dateiname und Status mit der Minecraft-Bitmap-Schrift und ganzzahligen Schriftgrößen. Lange Titel werden in höchstens zwei zentrierte Zeilen umbrochen. Ein Visualizer mit 24 logarithmischen Frequenzbändern wird aus dem tatsächlich wiedergegebenen PCM berechnet (Hann-Fenster, 1024-Punkt-FFT), ohne die Audiodaten zu verändern. Er analysiert das Signal vor der Lautstärkeanpassung. Bei Pause bleibt die Anzeige stehen; Spulen leert die alten Spektraldaten, Stopp zeigt ruhende Balken. Die gemeinsame Spektralanalyse läuft bei der PCM-Ausgabe und nur für Audio im Mediaplayer, unabhängig vom Backend.

Audio und Videos zeigen unten einen Fortschrittsbalken mit verstrichener Zeit und Gesamtdauer. Die Position folgt der Medienzeit, einschließlich Pause und Spulen; unbekannte Dauer erscheint als `--:--`. Bei Dateiende ist der Balken vollständig, bei Neustart beginnt er wieder bei null. Bilder besitzen keine Wiedergabedauer. Die darunterliegende Zurück-Leiste bleibt anklickbar.

## Bedienung

- Hoch/runter beziehungsweise W/S wählt einen Eintrag; OK, Enter, Space oder rechts/D öffnet ihn. Direkte Klicks öffnen Einträge ebenfalls.
- Links/A oder Zurück/Escape geht zum übergeordneten Ordner. Am Stammordner bleibt die App dort.
- Seite hoch/runter oder die untere Seitenleiste blättert durch längere Listen. Scrollen bewegt die Auswahl.
- Während einer Datei beendet Zurück/Escape die einzelne Wiedergabe und kehrt in denselben Ordner zurück. Die Hinweisleiste unten ist ebenfalls anklickbar.
- Pause, zehn Sekunden vor/zurück, Lautstärke und Stumm verwenden die Fähigkeiten des gewählten Backends und der vorhandenen Fernbedienung. Bilder bieten keine Pause-/Spulfunktion.
- Während der Wiedergabe schalten Space/Enter und Klicks auf die Bildfläche Pause um; links/rechts spult zehn Sekunden. Scrollen verändert die Lautstärke. Live-Streams bieten keine Pause-/Spulfunktionen.
- Stopp beendet die Datei innerhalb des Mediaplayers. Wiedergabe startet diese Datei erneut von Anfang an. Auch am Dateiende ist erneutes Starten möglich.
- SmartTV-Home beendet die gesamte App. Die SmartTV-Einstellungen bleiben ein Overlay über der laufenden Wiedergabe.

`boiler.command.action` erlaubt Bedienung und Navigation. Zum Start einer Datei wird zusätzlich `boiler.command.source` geprüft. Das Einstellungs-Overlay behält `boiler.command.settings` bei.

## Speicherung und Lebenszyklus

Der zuletzt besuchte Ordner wird als relativer Wert `directory` in den bestehenden Quelldaten gespeichert. Nach Neustart öffnet der Mediaplayer diesen Ordner, ohne automatisch eine Datei abzuspielen. Im SmartTV startet nach Serverneustart weiterhin zunächst die TV-Startseite. Beim Browsen werden absolute lokale Medienpfade nicht in die SmartTV-Parameter geschrieben. Direkte URL-Starts speichern ihre übergebenen Wiedergabeparameter und können beim Wiederherstellen direkt starten. Aufgelöste temporäre YouTube-/Twitch-URLs ersetzen die ursprünglichen Anbieterparameter nicht. Zurück aus direkter Wiedergabe öffnet den lokalen Browser.

Ordnerlisten und Pfadprüfungen laufen auf I/O-Workern. Späte Ergebnisse nach Zurück, einer neueren Auswahl oder dem Entfernen der Quelle werden verworfen. Bilder verwenden eine untergeordnete Quellensitzung; Audio und Video eine eigene abbrechbare Wiedergabesitzung mit internen Backends. Native Lade-, Steuerungs- und Freigabeoperationen laufen außerhalb des Serverthreads. Stopp, Zurück, Home und Plugin-Abschaltung geben Decoder und Audioausgabe frei.

Im Dateibrowser werden Pfade kanonisch gegen den Medienordner geprüft. Explizit über Befehl/API übergebene lokale Pfade sind davon unabhängig und erfordern die Berechtigung zum Setzen einer Quelle. `..`, absolute externe Pfade und Verknüpfungen zu Zielen außerhalb von `media` können nicht geöffnet werden. Auch der Medienordner selbst darf nicht per Verknüpfung aus dem Pluginordner herausführen. Eine Datei wird vor jedem Start erneut geprüft; gelöschte oder veränderte Einträge lassen sich durch Aktualisieren neu einlesen.

## Prüfung

Zusätzliche Tests prüfen Backendauswahl ohne Fallback, Config-Neuladen, direkte URLs, Windows-Pfade mit Leerzeichen, YouTube-/Twitch-Weiterleitung und Erhalt der Anbieterparameter, Abbruch während des Ladens sowie verspätete Backendereignisse. Automatisierte Tests decken Ordneranlage, Sortierung, Dateitypen, Pfadgrenzen, Symlinks (sofern vom Testsystem erlaubt), Navigation, Klickflächen, Rechte, Bild-/Audio-/Videorouting, eine echte PNG-Datei, verspätete Ergebnisse nach Unload, Stopp/Neustart, Dateiende und SmartTV-Einstellungen/Home ab. Vorschauen für mehrere Displaygrößen liegen nach dem Test in `plugin/build/reports/mediaplayer-preview`.

Die Live-Abnahme auf Paper benötigt echte lokale Videos und Audiodateien samt Sprachchat: Ordner durchklicken, Ton und Bild prüfen, Pause/Spulen testen, im Einstellungs-Overlay weiterlaufen lassen und mit Zurück beziehungsweise Home verlassen. Dabei auch eine währenddessen entfernte Datei sowie Serverneustart und Plugin-Abschaltung prüfen.

### Verifikationsgrenzen

Die nativen FFmpeg-Tests erzeugen echte Video-/Audiodateien und prüfen Zeitsteuerung, Pause, Seek, Loop, Resampling und Ressourcenfreigabe. Austauschbare Test-Backends prüfen den gemeinsamen Lebenszyklus und fehlende native Bibliotheken. Ein Test-Durchlauf ersetzt keine Live-Abnahme von GStreamer/libVLC auf Paper mit Minecraft-Client und Voicechat. Diese Live-Abnahme und externe Netzwerkstreams sind bei diesem Umbau nicht verifiziert worden.

Ein optionaler nativer Windows-Test lädt die vorhandene VLC-Installation aus dem Standardverzeichnis auch unter einem simulierten Server-Classloader ohne Zugriff auf die Plugin-Suchmodule. Dieser Bibliotheks-Ladetest wurde mit VLC 3.0.23 erfolgreich ausgeführt; er ersetzt keine vollständige Wiedergabeprüfung auf Paper.
