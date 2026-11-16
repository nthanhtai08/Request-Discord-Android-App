# Build notes

## Video Quest handling

The previous `processVideoQuest()` implementation advanced a local timer and sent synthetic `video-progress` timestamps without actual media playback. That behavior has been removed. The app now logs the server-reported progress and leaves the Quest state unchanged. It does not claim completion or auto-claim a reward for a video Quest that Discord has not marked complete.

This source package has not been rebuilt into a new APK in this environment because the Android SDK and Gradle executable/wrapper are not available here. Build on an Android/Gradle environment with:

```bash
./gradlew assembleDebug
```

The resulting APK is normally at `app/build/outputs/apk/debug/app-debug.apk`.
