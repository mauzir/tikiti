# Tikiti

Odtwarzacz YouTube na mapach w Minecraft. Wymaga `yt-dlp` i `ffmpeg` na serwerze.

Opcjonalnie zainstaluj [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) na serwerze i u graczy.
Wtedy ścieżka dźwiękowa filmu jest słyszalna pozycyjnie od środka ekranu. Domyślny promień to 20 bloków (`audio-distance` w `config.yml`). Bez Voice Chatu obraz nadal działa, tylko bez dźwięku.

## Komendy

```text
/tikiti <link YouTube> [szerokość] [wysokość]
/tikiti pause
/tikiti resume
/tikiti seek 1:30
/tikiti seek 90
/tikiti seek +30
/tikiti seek -10
/tikiti time
/tikiti stop
```

Polecenia mają podpowiedzi po naciśnięciu Tab. Pauza zachowuje ostatnią klatkę, a wznowienie i przewijanie uruchamiają strumień ponownie od wskazanego czasu.
