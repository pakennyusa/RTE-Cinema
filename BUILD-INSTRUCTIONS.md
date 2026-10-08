# RTE Cinema build and installation

This is an unverified beta. To compile: open the Actions tab, select "Build RTE Cinema", click "Run workflow", and download the RTE-Cinema-JAR artifact if the job passes.

Alternatively install JDK 25 and Gradle 9.1, then run `gradle clean build` and use `build/libs/RTE-Cinema-0.1.0-beta.jar`.

Install FFmpeg on the Paper server and ensure the `ffmpeg` executable is on PATH. Copy the JAR into `plugins/`, restart Paper, and put an authorized MP4 into `plugins/RTECinema/media/`.

In game, face the bottom-left wall block and run:
```
/cinema create theater 2 1
/cinema select theater
/cinema play example.mp4
/cinema gui
```

This plugin uses map rendering and is silent (no native in-game audio), not direct YouTube/Twitch playback. Set fps, view-distance, and maximum screen dimensions in config.yml to manage bandwidth. Test on a staging server before production.