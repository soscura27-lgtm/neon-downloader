#!/usr/bin/env python3
"""Comprueba que las fuentes de letras de la app siguen respondiendo como la app espera.

Hace las mismas peticiones que app/src/main/java/com/deniscerri/ytdl/ui/lyrics/ (YouTube Music y LRCLIB) con
algunos vídeos conocidos y enseña lo que llega: nombre real de la canción, tipo de vídeo, si hay letra, si viene
con tiempos y de dónde. No compila nada. Lo usa .github/workflows/probar-letras.yml.

Uso: python3 scripts/ci/probar_letras.py [idDeVideo ...]
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://music.youtube.com/youtubei/v1/"
ORIGIN = "https://music.youtube.com"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
ANDROID_VERSION = "7.21.50"
LRCLIB = "https://lrclib.net/api/"
LRCLIB_AGENT = "YTDLnis (https://github.com/deniscerri/ytdlnis)"

# Vídeos de prueba: videoclips oficiales conocidos (de cada uno se prueba también su canción, si YouTube Music la da)
VIDEOS = ["kJQP7kiw5Fk", "dQw4w9WgXcQ", "fJ9rUzIMcZQ", "JGwWNGJdvx8"]

visitor_data = None
problemas = []


def web_version():
    return "1." + time.strftime("%Y%m%d", time.gmtime()) + ".01.00"


def context(client, version):
    c = {"clientName": client, "clientVersion": version, "hl": "es", "gl": "MX"}
    if visitor_data:
        c["visitorData"] = visitor_data
    return {"client": c, "user": {}}


def post(endpoint, body):
    global visitor_data
    headers = {
        "Content-Type": "application/json",
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Origin": ORIGIN,
        "Referer": ORIGIN + "/",
    }
    if visitor_data:
        headers["X-Goog-Visitor-Id"] = visitor_data
    req = urllib.request.Request(API + endpoint + "?prettyPrint=false", data=json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            data = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print(f"    {endpoint}: HTTP {e.code}")
        return None
    vd = (data.get("responseContext") or {}).get("visitorData")
    if vd:
        visitor_data = vd
    return data


def obj(d, *keys):
    for k in keys:
        if not isinstance(d, dict):
            return None
        d = d.get(k)
    return d


def runs_text(d, key):
    f = (d or {}).get(key) or {}
    runs = f.get("runs")
    if runs is None:
        return f.get("simpleText", "")
    return "".join(r.get("text", "") for r in runs)


def duration(text):
    try:
        parts = [int(p) for p in text.strip().split(":")]
    except ValueError:
        return 0
    total = 0
    for p in parts:
        total = total * 60 + p
    return total


def parse_track(video, counterpart):
    artists, album, group = [], "", 0
    for run in obj(video, "longBylineText", "runs") or []:
        text = run.get("text", "")
        if text.strip() == "•":
            group += 1
            continue
        browse_id = obj(run, "navigationEndpoint", "browseEndpoint", "browseId") or ""
        if browse_id.startswith("MPRE"):
            album = text.strip()
        elif group == 0 and text.strip() and text.strip() not in {"&", ",", "y", "and", "x", "·"}:
            artists.append(text.strip())
    return {
        "videoId": video.get("videoId"),
        "title": runs_text(video, "title").strip(),
        "artists": artists,
        "album": album,
        "duration": duration(runs_text(video, "lengthText")),
        "type": obj(video, "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs",
                    "watchEndpointMusicConfig", "musicVideoType") or "",
        "counterpart": duration(runs_text(counterpart, "lengthText")) if counterpart else 0,
        "counterpartId": (counterpart or {}).get("videoId"),
    }


def info(video_id):
    data = post("next", {"context": context("WEB_REMIX", web_version()), "videoId": video_id, "isAudioOnly": True})
    if data is None:
        return None
    tabs = obj(data, "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
               "watchNextTabbedResultsRenderer", "tabs")
    if not tabs:
        print("    next: sin pestañas (cambió el formato)")
        problemas.append(f"{video_id}: next sin pestañas")
        return None
    lyrics_id, queue = None, None
    for i, tab in enumerate(tabs):
        tr = tab.get("tabRenderer") or {}
        q = obj(tr, "content", "musicQueueRenderer", "content", "playlistPanelRenderer", "contents")
        if q and queue is None:
            queue = q
        browse = obj(tr, "endpoint", "browseEndpoint") or {}
        page = obj(browse, "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType") or ""
        bid = browse.get("browseId") or ""
        print(f"    pestaña {i}: pageType={page or '-'} browseId={bid[:24] or '-'} unselectable={tr.get('unselectable')}")
        if bid and (page == "MUSIC_PAGE_TYPE_TRACK_LYRICS" or (not page and i == 1)):
            lyrics_id = bid
    current = None
    for item in queue or []:
        wrapper = item.get("playlistPanelVideoWrapperRenderer")
        video = item.get("playlistPanelVideoRenderer") or obj(wrapper, "primaryRenderer", "playlistPanelVideoRenderer")
        if not video:
            continue
        cp = obj((obj(wrapper, "counterpart") or [{}])[0], "counterpartRenderer", "playlistPanelVideoRenderer") if wrapper else None
        track = parse_track(video, cp)
        if video.get("videoId") == video_id or video.get("selected"):
            current = track
            break
        if current is None:
            current = track
    if current is None:
        print("    next: sin la pista actual en la cola")
        problemas.append(f"{video_id}: next sin cola")
    return current, lyrics_id


def timed(data):
    d = obj(data, "contents", "elementRenderer", "newElement", "type", "componentType", "model",
            "timedLyricsModel", "lyricsData")
    if not d or not d.get("timedLyricsData"):
        return None
    lines = []
    for item in d["timedLyricsData"]:
        start = obj(item, "cueRange", "startTimeMilliseconds")
        if start is None:
            continue
        lines.append((int(str(start)), (item.get("lyricLine") or "").strip()))
    return lines, d.get("sourceMessage", "")


def plain(data):
    for c in obj(data, "contents", "sectionListRenderer", "contents") or []:
        shelf = c.get("musicDescriptionShelfRenderer")
        if not shelf:
            continue
        text = runs_text(shelf, "description").strip()
        if text:
            return text, runs_text(shelf, "footer").strip()
    return None


def lyrics(browse_id):
    a = post("browse", {"context": context("ANDROID_MUSIC", ANDROID_VERSION), "browseId": browse_id})
    t = timed(a) if a else None
    if t:
        lines, src = t
        print(f"    letra (Android): CON TIEMPOS, {len(lines)} líneas, fuente={src!r}")
        for ms, text in lines[:3]:
            print(f"      [{ms / 1000:7.2f}] {text}")
        return "timed", lines[-1][0] if lines else 0
    p = plain(a) if a else None
    if p:
        print(f"    letra (Android): solo texto, {len(p[0].splitlines())} líneas, fuente={p[1]!r}")
    else:
        keys = list((a or {}).get("contents", {}).keys()) if a else None
        print(f"    letra (Android): nada (contents={keys})")
    w = post("browse", {"context": context("WEB_REMIX", web_version()), "browseId": browse_id})
    p2 = plain(w) if w else None
    if p2:
        first = [l for l in p2[0].splitlines() if l.strip()][:2]
        print(f"    letra (web): solo texto, {len(p2[0].splitlines())} líneas, fuente={p2[1]!r} -> {first}")
        return "plain", 0
    print("    letra (web): nada")
    return None, 0


def lrclib(path, params):
    url = LRCLIB + path + "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v})
    req = urllib.request.Request(url, headers={"User-Agent": LRCLIB_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"http": e.code}


def probe(video_id, follow_counterpart=True):
    print(f"\n=== {video_id} ===")
    result = info(video_id)
    if not result:
        return
    track, lyrics_id = result
    if not track:
        return
    matches = track["type"] in ("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC") or (
        track["type"] == "MUSIC_VIDEO_TYPE_OMV" and track["counterpart"] > 0 and track["duration"] > 0
        and abs(track["counterpart"] - track["duration"]) <= 3)
    print(f"    canción={track['title']!r} artistas={track['artists']} álbum={track['album']!r}")
    print(f"    duración={track['duration']}s tipo={track['type'] or '-'} otra versión={track['counterpartId']} "
          f"({track['counterpart']}s) -> tiempos valen para este vídeo: {matches}")
    if lyrics_id:
        kind, last = lyrics(lyrics_id)
        if kind == "timed" and track["duration"] and last > (track["duration"] + 5) * 1000:
            problemas.append(f"{video_id}: la última línea ({last} ms) va después del final del vídeo")
    else:
        print("    sin pestaña de letra")
    if track["title"] and track["artists"]:
        g = lrclib("get", {"track_name": track["title"], "artist_name": track["artists"][0],
                           "duration": str(track["duration"]) if track["duration"] else None})
        if "http" in g:
            print(f"    LRCLIB get: HTTP {g['http']}")
        else:
            print(f"    LRCLIB get: id={g.get('id')} {g.get('artistName')!r} - {g.get('trackName')!r} "
                  f"dura={g.get('duration')} con tiempos={bool(g.get('syncedLyrics'))}")
        s = lrclib("search", {"q": f"{track['artists'][0]} {track['title']}"})
        if isinstance(s, list):
            print(f"    LRCLIB search: {len(s)} resultados; primero="
                  f"{(s[0].get('artistName'), s[0].get('trackName'), s[0].get('duration')) if s else None}")
    if follow_counterpart and track["counterpartId"]:
        probe(track["counterpartId"], follow_counterpart=False)


def main():
    videos = sys.argv[1:] or VIDEOS
    for v in videos:
        try:
            probe(v)
        except Exception as e:  # noqa: BLE001 - es un diagnóstico: se cuenta y se sigue
            print(f"    ERROR: {type(e).__name__}: {e}")
            problemas.append(f"{v}: {type(e).__name__}: {e}")
    print("\nvisitorData recibido:", bool(visitor_data))
    if problemas:
        print("\nPROBLEMAS:")
        for p in problemas:
            print(" -", p)
        sys.exit(1)
    print("\nTodo responde como espera la app.")


if __name__ == "__main__":
    main()
