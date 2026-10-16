// The approval clip loops silently while it is on screen. It ships with controls so it works without
// JavaScript; with reduced motion it keeps them and stays paused on its poster until the visitor plays it.
(() => {
  const clips = document.querySelectorAll('video[data-loop-clip]');
  if (!clips.length) return;
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

  const play = (clip) => clip.play().catch(() => { clip.controls = true; });
  for (const clip of clips) {
    clip.muted = true;
    clip.controls = false;
    if (!('IntersectionObserver' in window)) { play(clip); continue; }
    new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) play(clip);
      else clip.pause();
    }, { threshold: 0.25 }).observe(clip);
  }
})();
