# RTE Cinema 0.2.0 beta — Native audio and per-screen looping

**Experimental build; test on a staging server first.** Vanilla Minecraft has no native live audio streaming. This implementation converts an authorized local movie's audio into 4-second Vorbis OGG segments and creates a Minecraft resource pack. Players must accept the pack and have access to the HTTPS download URL.

## Setup

1. Install FFmpeg on the **server** (the same FFmpeg used for video). Check `ffmpeg-path` in `plugins/RTECinema/config.yml`.
2. Put a movie (MP4, WebM, MOV, or MKV) in `plugins/RTECinema/media/`.
3. In game, with a selected cinema, run `/cinema audio prepare mymovie.mp4`. FFmpeg prepares audio asynchronously.
4. Find `plugins/RTECinema/RTE-Cinema-Audio.zip` and upload it **unchanged** to a publicly reachable HTTPS file host that sends ZIP bytes directly.
5. Set `audio.pack-url` in `plugins/RTECinema/config.yml` to the direct HTTPS URL. Retain `audio.pack-format: 97` for Minecraft 26.3.
6. Restart the server to load the config. Run `/cinema audio pack` and **accept the server resource pack** in Minecraft.
7. Check `/cinema audio status`, then play the movie with `/cinema play mymovie.mp4`.

**Important:** When preparing more audio, RTE Cinema regenerates the ZIP; upload the new file to your HTTPS host and use `/cinema audio pack` again. The ZIP must match the server's SHA-1. Existing packs won't contain the new movie's sounds. Packs can become very large for feature-length films. Test with short media first.

## Loop by theater

Select a theater with `/cinema select theater1`, then:

- `/cinema loop on`
- `/cinema loop off`
- `/cinema loop toggle`
- `/cinema loop status`

The selected theater's setting persists in `screens.yml` and the inventory GUI includes a Loop toggle. When FFmpeg reaches end of video, the theater starts playback from time zero if looping is on; audio segments restart with it.

## Limitations

- Audio runs as **best-effort** sync tied to video frames, not sample-accurate synchronization.
- Audio is localized to the theater and requires the viewer to accept the pack. Players arriving mid-segment may not hear that segment until the next boundary.
- No automatic hosting of the ZIP is included. Configure an HTTPS host that can be reached by each player.
- If a video has no audio stream, preparation fails with a message.
- Native audio has no live YouTube/Twitch support; use only media you have rights to distribute.
- Large resource packs may exceed client/server limits; test short material first and monitor resource pack size.
- If you play a movie without preparing audio, the video still plays silently.
- The audio mute command is not exposed yet; `audio.volume` is a server-side configuration volume.
