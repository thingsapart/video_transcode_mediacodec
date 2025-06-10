# VideOpt - A simple video transcoding app using MediaCodec + Hardware Acceleration

A lot of this vibe-coded with Jules. Seems to work for what I need it to do though.

## Why?

**Speed.** Plain and simple. 

Uses Android Hardware acceleration if possible using [Android's MediaCodec API](https://developer.android.com/reference/android/media/MediaCodec) via [deepmedia/Transcoder](https://github.com/deepmedia/Transcoder).

I love and use FFShare, but ffmpeg-based transoding makes it a litte slow.

## Screenshots

![Settings Screenshot](https://github.com/thingsapart/video_transcode_mediacodec/blob/main/docs/settings.jpeg?raw=true)
![Transcode Screenshot](https://github.com/thingsapart/video_transcode_mediacodec/blob/main/docs/transcoding.jpeg?raw=true)
![Transcode Done Screenshot](https://github.com/thingsapart/video_transcode_mediacodec/blob/main/docs/transcoded.jpeg?raw=true)

## How To
Run VideOpt and set your transcoding settings. Then whenever you need to, share a Video to VideOpt and it will automatically transcode with the predefined settings and allow to Reshare to another app. You can also save the video file to Downloads by sharing the video with "File Manager".

## Numbers, For Example

Transcoding a 120MB large, 31s long video to H.265 (FFShare made it 720x306, VideOpt 480p - 854x480) took about 1m30s on my Pixel 9 Pro on FFShare and resulted in a 3.2MB file, vs ~ 18s in VideOpt and a 4.9MB file.
