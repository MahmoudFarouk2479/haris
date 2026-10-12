// One-shot webcam photos for the core. The camera is opened only after the user approved
// the photo, kept on for about a second (the camera light and the HUD badge show it), and
// closed immediately after the single frame is taken.
import { ApiError, sessionToken } from './api.js';

const WARMUP_MS = 900;   // let auto-exposure settle so the photo is not black

export async function takePhoto(id, { onCameraState, onFlash }) {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false });
  } catch (e) {
    const reason = e?.name === 'NotAllowedError' ? 'denied' : e?.name === 'NotFoundError' ? 'missing' : 'unknown';
    await post(`/api/vision/camera/${encodeURIComponent(id)}/error?reason=${reason}`, null, 'application/json');
    return;
  }
  onCameraState?.(true);
  try {
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.srcObject = stream;
    await video.play();
    await new Promise((r) => setTimeout(r, WARMUP_MS));
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext('2d').drawImage(video, 0, 0);
    onFlash?.();
    const blob = await new Promise((r) => canvas.toBlob(r, 'image/jpeg', 0.85));
    await post(`/api/vision/camera/${encodeURIComponent(id)}`, blob, 'image/jpeg');
  } finally {
    stream.getTracks().forEach((t) => t.stop());
    onCameraState?.(false);
  }
}

async function post(path, body, type) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'X-Haris-Token': sessionToken() ?? '', 'Content-Type': type },
    body: body ?? '',
  });
  if (!res.ok && res.status !== 410) throw new ApiError(`Camera upload failed (${res.status})`);
}
