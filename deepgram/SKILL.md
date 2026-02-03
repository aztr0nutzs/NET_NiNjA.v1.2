
# Skills — Deepgram JavaScript SDK

## What It Is

Official JavaScript/TypeScript SDK for Deepgram’s speech and language AI services.
Supports real-time and batch speech-to-text, text-to-speech, voice agents, audio analysis, and more.  

---

## Installation

```
npm install @deepgram/sdk
```

or

```
yarn add @deepgram/sdk
```

Browser (UMD/ESM CDN) imports also available. 

---

## Authentication

Create a client with an API key (recommended) or access token:

```js
import { createClient } from "@deepgram/sdk";

const deepgramClient = createClient("YOUR_API_KEY");
```

SDK also picks up `DEEPGRAM_API_KEY` / `DEEPGRAM_ACCESS_TOKEN` from env. 

---

## Pre-Recorded Speech-to-Text (Batch)

Good for files already recorded.

### Remote URL

```js
const { result, error } = await deepgramClient.listen.prerecorded.transcribeUrl(
  { url: "https://dpgr.am/spacewalk.wav" },
  { model: "nova-3", smart_format: true }
);
console.log(result);
```

### Local File

```js
import fs from "fs";
const fileBuffer = fs.readFileSync("audio.mp3");

const { result, error } = await deepgramClient.listen.prerecorded.transcribeFile(
  fileBuffer,
  { model: "nova-3", smart_format: true }
);
console.log(result);
```

Outputs full transcript JSON with text, confidence, timestamps, etc. ([developers.deepgram.com][3])

---

## Live Streaming Speech-to-Text (WebSocket)

Useful for real-time audio (radio stream, mic, etc.).

```js
import { createClient, LiveTranscriptionEvents } from "@deepgram/sdk";

const deepgram = createClient(process.env.DEEPGRAM_API_KEY);
const connection = deepgram.listen.live({
  model: "nova-3",
  language: "en-US",
  smart_format: true,
});

connection.on(LiveTranscriptionEvents.Open, () => {
  connection.on(LiveTranscriptionEvents.Transcript, (data) => {
    console.log("LIVE:", data.channel.alternatives[0].transcript);
  });
});

// Feed audio bytes to Deepgram
// e.g., fetch stream, mic input, etc.
```

Emits transcript events as audio arrives.  

---

## Reading & Analyzing Audio Content

Deepgram doesn’t store transcripts — you *must* keep/save results in your app.
After transcription, parse JSON to extract text, timestamps, speaker segments, confidence, etc.  

---

## How to *Read/Reach* Transcription Data

Typical output structure:

```
{
  "results": {
    "channels": [
      {
        "alternatives": [
          {
            "transcript": "hello world …",
            "confidence": 0.99,
            "words": [...]
          }
        ]
      }
    ]
  }
}
```

Access text via:

```js
result.results.channels[0].alternatives[0].transcript
```

Use `words` for timestamps/word-level data.  

---

## Options & Enhancements

* **model** — choose Deepgram model (e.g., `"nova-3"`). 
* **smart_format** — auto-format numbers, dates, etc. 
* Callbacks available for async/transcription status. 

---

## Handy Extras

* Captions (WebVTT/SRT) via SDK helpers. 
* Proxy setup for browser use of REST APIs. 

---

## Quick Skill Summary

* Install and init Deepgram client
* Transcribe remote/local files
* Stream real-time audio with WebSockets
* Parse transcript JSON (text ↔ timestamps)
* Use Deepgram options (model, smart_format, language)
 
