# RTE Cinema v0.3 beta: single-track native audio

Single audio mode removes 4-second sound sequencing. A movie's full audio is converted to one Vorbis OGG and registered as a streaming sound in a Minecraft resource pack. The movie video remains map-rendered. When the first video frame is broadcast, the plugin triggers the full OGG sound once for viewers in range with the new pack loaded. Looping restarts the video and audio at the beginning.

## Install and configure

1. Build the newest GitHub Actions commit and install the v0.3 beta JAR on a test server.
2. In plugins/RTECinema/config.yml, set:
   ```yaml
   audio:
     mode: single
     pack-url: 'https://your-real-host/RTE-Cinema-Audio.zip'
     pack-format: 97
     volume: 1.0
     offset-ms: 0
     debug: false
   ```
3. Restart Paper. With the media file in plugins/RTECinema/media/, run `/cinema audio prepare filename.mp4`. It creates plugins/RTECinema/audio/<media-id>/full.ogg and regenerates RTE-Cinema-Audio.zip.
4. Upload the **new** RTE-Cinema-Audio.zip to your HTTPS host, replacing the previous pack. Confirm the URL downloads the actual ZIP.
5. Run `/cinema audio pack` and accept the new pack. The pack hash must match the newly generated ZIP.
6. Run `/cinema select theatre1` and `/cinema play filename.mp4`. `/cinema audio test` tests the whole OGG in single mode.

## Caveats

- A large single OGG can result in a very large resource pack, potentially exceeding hosting or Minecraft client limits. Start with a 20–30-second test movie, inspect ZIP size, and test performance.
- Minecraft cannot seek into a playing custom sound. In single mode, pause stops audio and resume **restarts both video and audio from the beginning**. Stop and theater looping also stop/restart the single track. Viewers arriving after the opening trigger do not automatically join partway through audio.
- Multiple users may perceive different small delays. True frame-accurate sync is unavailable without a client mod.
- Existing prepared segmented packs don't contain the new full.ogg until you rerun `audio prepare` and upload the regenerated ZIP.
- Only play and distribute media/audio you are authorized to distribute.
