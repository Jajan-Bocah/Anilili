# Error codes

Generated from `AnililiError` by `ErrorCatalogTest`. Do not edit by hand.

Every failure the app can show carries a code. Quote it in a bug report: it is the only
thing that survives a minified release build and can be matched against a diagnostics
bundle, where the same code is the event name.

A code may carry a second half — `PLAY-02/h403`. That is the underlying library's own
error number, kept because it is more precise than anything this app invents:

| Prefix | Source |
| --- | --- |
| `h` | HTTP response status |
| `m` | Media3 `PlaybackException.errorCode` |
| `x` | Media3 `ExportException.errorCode` |
| `c` | `MediaCodec.CodecException.errorCode` |

## Network — `NET`

| Code | What it means | Next step |
| --- | --- | --- |
| `NET-01` | No connection. | Try again |
| `NET-02` | The server took too long to answer. | Try again |
| `NET-03` | Couldn't reach the server. Check your private DNS or VPN. | Try again |
| `NET-04` | This device couldn't verify the server's certificate. | Send diagnostics |
| `NET-05` | Couldn't load the catalogue. | Try again |
| `NET-06` | Couldn't load the home page. | Try again |
| `NET-07` | Couldn't load the schedule. | Try again |
| `NET-08` | Couldn't load notifications. | Try again |
| `NET-09` | Search didn't come back. | Try again |
| `NET-10` | A server is asking this device to prove it isn't a bot. | Try again |
| `NET-11` | Couldn't load this title. | Try again |
| `NET-12` | Your network is blocking this server. Try another network or a VPN. | — |
| `NET-13` | Couldn't pick a random anime. | Try again |

## Sources — `SRC`

| Code | What it means | Next step |
| --- | --- | --- |
| `SRC-01` | No source lists episodes for this title yet. | Try again |
| `SRC-02` | No source lists this episode yet. | Try again |
| `SRC-03` | No server has a playable copy of this episode. | Try another server |
| `SRC-04` | This server has nothing playable for this episode. | Try another server |
| `SRC-05` | Couldn't load this episode. | Try again |
| `SRC-06` | The server refused to hand over this episode. | Try another server |
| `SRC-07` | No server offered a stream another video player can open. | Try another server |
| `SRC-08` | No server offered a downloadable copy. | Try another server |
| `SRC-09` | This server is limiting requests right now. | Try another server |

## Playback — `PLAY`

| Code | What it means | Next step |
| --- | --- | --- |
| `PLAY-01` | Playback stopped. | Try again |
| `PLAY-02` | Lost the connection to this server. | Try another server |
| `PLAY-03` | This device couldn't decode the video. | Try another server |
| `PLAY-04` | This device couldn't decode the audio. | Try another server |
| `PLAY-05` | Couldn't play this download. | Send diagnostics |
| `PLAY-06` | The web player didn't load. | Try another server |
| `PLAY-07` | No other video player is installed to open this with. | Open settings |
| `PLAY-08` | This copy can't be handed to another player. | — |
| `PLAY-09` | No app on this device can open a web page. | — |
| `PLAY-10` | Your network is blocking this video server. | Try another server |

## Subtitles — `SUB`

| Code | What it means | Next step |
| --- | --- | --- |
| `SUB-01` | No subtitle tracks for this video. | — |
| `SUB-02` | Couldn't read that subtitle file. | Choose a file |
| `SUB-03` | No file picker on this device. | — |
| `SUB-04` | Pick a subtitle track first. | — |
| `SUB-05` | Subtitle search didn't come back. | Try again |
| `SUB-06` | Couldn't download that subtitle. | Try again |
| `SUB-07` | Sign in to OpenSubtitles in Settings first. | Open settings |
| `SUB-08` | No lines were found near that moment. | — |
| `SUB-09` | Couldn't reach AniSkip. | Try again |

## Downloads — `DL`

| Code | What it means | Next step |
| --- | --- | --- |
| `DL-01` | Couldn't start the download. | Try again |
| `DL-02` | Download failed. | Try again |
| `DL-03` | Not enough space left on this device. | Free up space |
| `DL-04` | Download stalled. | Try again |
| `DL-05` | Couldn't save this episode's subtitles. | — |
| `DL-06` | No app can open these download options. | — |

## Saving to device — `EXP`

| Code | What it means | Next step |
| --- | --- | --- |
| `EXP-01` | Couldn't save this episode to %s. | Save again |
| `EXP-02` | Not enough free space to build the MP4. | Free up space |
| `EXP-03` | Not enough free space on %s. | Free up space |
| `EXP-04` | Couldn't save this episode as an MP4. It still plays in the app. | Save again |
| `EXP-05` | This device can't build MP4 files. Episodes still play in the app. | — |
| `EXP-06` | Built the MP4 but couldn't write it to %s. | Save again |
| `EXP-07` | Lost access to the chosen download folder. | Open settings |
| `EXP-08` | Storage permission is needed to save files here. | Open settings |
| `EXP-09` | Device storage isn't available right now. | — |
| `EXP-10` | The chosen save location isn't connected. | Open settings |
| `EXP-11` | That download is no longer on this device. | — |

## Accounts — `ACC`

| Code | What it means | Next step |
| --- | --- | --- |
| `ACC-01` | Sign in to AniList or MyAnimeList first. | Sign in |
| `ACC-02` | Sign in to %s again to continue. | Sign in |
| `ACC-03` | Couldn't load your %s profile. | Try again |
| `ACC-04` | Couldn't load your %s library. | Try again |
| `ACC-05` | Couldn't update %s. | Try again |
| `ACC-06` | Couldn't open the login page. | Try again |
| `ACC-07` | That sign-in didn't finish. | Sign in |
| `ACC-08` | Too many requests to %s. Try again in a minute. | Try again |
| `ACC-09` | Couldn't remove this from %s. | Try again |
| `ACC-10` | Couldn't connect %s. | Try again |
| `ACC-11` | Couldn't save your score on %s. | Try again |
| `ACC-12` | Couldn't save your episode count on %s. | Try again |

## Backup — `BAK`

| Code | What it means | Next step |
| --- | --- | --- |
| `BAK-01` | Couldn't read that file. | Choose a file |
| `BAK-02` | Couldn't write the export file. | Try again |
| `BAK-03` | That isn't an Anilili backup. | Choose a file |
| `BAK-04` | That isn't a MyAnimeList export file. | Choose a file |
| `BAK-05` | No anime entries in that file. | Choose a file |
| `BAK-06` | Nothing here can be exported. | — |
| `BAK-07` | No file picker on this device. | — |

## TV pairing — `PAIR`

| Code | What it means | Next step |
| --- | --- | --- |
| `PAIR-01` | Nothing is listening on %s. | Try again |
| `PAIR-02` | No reply from %s. | Try again |
| `PAIR-03` | Can't reach %s from this phone. | — |
| `PAIR-04` | That isn't an address this phone understands. | — |
| `PAIR-05` | The television closed the connection. | Try again |
| `PAIR-06` | The two devices disagreed. | — |
| `PAIR-07` | No televisions found on this network. | Try again |
| `PAIR-08` | Nothing connected in time. | Try again |
| `PAIR-09` | Pairing didn't complete. | Try again |

## Updates — `UPD`

| Code | What it means | Next step |
| --- | --- | --- |
| `UPD-01` | Couldn't check for updates. | Try again |
| `UPD-02` | No signed update was found. | Try again |
| `UPD-03` | The update didn't finish downloading. | Try again |
| `UPD-04` | Couldn't make room for the update. | Free up space |
| `UPD-05` | This device refused to install the update. | — |
| `UPD-06` | No installer on this device accepted the update. | — |

## Diagnostics — `DIAG`

| Code | What it means | Next step |
| --- | --- | --- |
| `DIAG-01` | Couldn't prepare the diagnostics report. | Try again |
| `DIAG-02` | Couldn't read the selected screenshot. | Choose a file |
| `DIAG-03` | Couldn't record diagnostics on this device. | — |

## Links — `DLINK`

| Code | What it means | Next step |
| --- | --- | --- |
| `DLINK-01` | That Anilili link isn't one this app understands. | — |

## App — `APP`

| Code | What it means | Next step |
| --- | --- | --- |
| `APP-01` | Something went wrong. | Send diagnostics |
