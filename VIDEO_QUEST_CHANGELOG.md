# Video Quest progress logging

- Video quest types (`WATCH_VIDEO` and `WATCH_VIDEO_ON_MOBILE`) are recognized as `WATCH_VIDEO`.
- The app reads authoritative progress from `GET /quests/@me` every 10 seconds while monitoring a video quest.
- Progress logs show seconds and percentage only when the server-reported value changes.
- The video monitor does not submit `video-progress` timestamps, simulate playback, or claim rewards.
