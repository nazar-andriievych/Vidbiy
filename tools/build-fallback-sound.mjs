// Generates app/src/main/res/raw/alarm_fallback.wav — the last-resort alarm sound.
// It ships inside the APK, so it plays even when every system ringtone URI is broken.
// Usage: node tools/build-fallback-sound.mjs

import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const SAMPLE_RATE = 16000;
const FREQUENCY = 1000; // Hz, close to where hearing is most sensitive
const AMPLITUDE = 0.9;
const FADE_MS = 5; // softens each beep's edges to avoid clicks

// One 1-second cycle, looped by the player: beep, gap, beep, long gap.
const PATTERN = [
  { ms: 150, tone: true },
  { ms: 100, tone: false },
  { ms: 150, tone: true },
  { ms: 600, tone: false },
];

const samples = [];
for (const { ms, tone } of PATTERN) {
  const count = Math.round((SAMPLE_RATE * ms) / 1000);
  const fade = Math.round((SAMPLE_RATE * FADE_MS) / 1000);
  for (let i = 0; i < count; i++) {
    if (!tone) {
      samples.push(0);
      continue;
    }
    const t = i / SAMPLE_RATE;
    const envelope = Math.min(1, i / fade, (count - 1 - i) / fade);
    // A bit of the third harmonic makes the tone harsher and easier to hear on small speakers.
    const wave = 0.8 * Math.sin(2 * Math.PI * FREQUENCY * t) + 0.2 * Math.sin(2 * Math.PI * 3 * FREQUENCY * t);
    samples.push(Math.round(wave * envelope * AMPLITUDE * 32767));
  }
}

const dataSize = samples.length * 2;
const wav = Buffer.alloc(44 + dataSize);
wav.write("RIFF", 0);
wav.writeUInt32LE(36 + dataSize, 4);
wav.write("WAVE", 8);
wav.write("fmt ", 12);
wav.writeUInt32LE(16, 16); // PCM header size
wav.writeUInt16LE(1, 20); // PCM
wav.writeUInt16LE(1, 22); // mono
wav.writeUInt32LE(SAMPLE_RATE, 24);
wav.writeUInt32LE(SAMPLE_RATE * 2, 28); // byte rate
wav.writeUInt16LE(2, 32); // block align
wav.writeUInt16LE(16, 34); // bits per sample
wav.write("data", 36);
wav.writeUInt32LE(dataSize, 40);
samples.forEach((s, i) => wav.writeInt16LE(s, 44 + i * 2));

const out = fileURLToPath(new URL("../app/src/main/res/raw/alarm_fallback.wav", import.meta.url));
writeFileSync(out, wav);
console.log(`Wrote ${out} (${wav.length} bytes)`);
